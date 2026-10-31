// Stave's own FFmpeg: the audio demuxers, decoders, encoders and the resampler behind a small
// JNI surface (com.layerbit.stave.native.AvNative). Built by tools/native/build.sh into
// app/src/main/jniLibs/<abi>/libstaveav.so with FFmpeg linked statically and nothing exported
// but the JNI entry points, so it cannot collide with any other copy of FFmpeg in the process.
//
// Three jobs:
//  - probe: what is in a file (JSON bytes - a tag that is not valid UTF-8 would make
//    NewStringUTF throw, so results are bytes and Kotlin decodes them);
//  - decode: one stream to raw interleaved 32-bit float at a chosen rate, into a file the graph
//    memory-maps, with progress and cancel;
//  - encode: a raw float file to any encoder in the build, with tags, cover art and chapters.
//
// Handles are pointers as longs; 0 is failure (AvHandles.kt). Every handle is used from one
// thread at a time, which the Kotlin wrappers arrange. FFmpeg 7.1+ APIs (AVChannelLayout,
// avcodec_get_supported_config).
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libavutil/audio_fifo.h>
#include <libswresample/swresample.h>

#define TAG "StaveAv"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define FN(name) Java_com_layerbit_stave_native_AvNative_##name

#define IO_BUFFER 65536

/* ------------------------------------------------------------------ */
/* A growable string, for JSON results.                                */
/* ------------------------------------------------------------------ */

typedef struct { char *data; size_t len, cap; } Str;

static void str_add(Str *s, const char *text, size_t n) {
    if (s->len + n + 1 > s->cap) {
        size_t cap = s->cap ? s->cap : 1024;
        while (s->len + n + 1 > cap) cap *= 2;
        char *grown = realloc(s->data, cap);
        if (!grown) return;
        s->data = grown; s->cap = cap;
    }
    memcpy(s->data + s->len, text, n);
    s->len += n;
    s->data[s->len] = 0;
}
static void str_cat(Str *s, const char *text) { str_add(s, text, strlen(text)); }
static void str_fmt(Str *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void str_fmt(Str *s, const char *fmt, ...) {
    char buf[512]; va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    if (n > 0) str_add(s, buf, n < (int) sizeof buf ? (size_t) n : sizeof buf - 1);
}
static void str_json(Str *s, const char *text) {
    str_cat(s, "\"");
    if (text) for (const unsigned char *p = (const unsigned char *) text; *p; p++) {
        char esc[8];
        switch (*p) {
            case '"': str_cat(s, "\\\""); break;
            case '\\': str_cat(s, "\\\\"); break;
            case '\n': str_cat(s, "\\n"); break;
            case '\r': str_cat(s, "\\r"); break;
            case '\t': str_cat(s, "\\t"); break;
            default:
                if (*p < 0x20) { snprintf(esc, sizeof esc, "\\u%04x", *p); str_cat(s, esc); }
                else str_add(s, (const char *) p, 1);
        }
    }
    str_cat(s, "\"");
}
static jbyteArray json_result(JNIEnv *env, Str *s) {
    if (!s->data) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) s->len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) s->len, (const jbyte *) s->data);
    free(s->data);
    return out;
}

/* ------------------------------------------------------------------ */
/* Reading from a file descriptor: pread, so the descriptor's own      */
/* offset is never touched and two readers can share one.             */
/* ------------------------------------------------------------------ */

typedef struct { int fd; int64_t pos; int64_t size; } FdIo;

static int fd_read(void *opaque, uint8_t *buf, int size) {
    FdIo *io = opaque;
    ssize_t n = pread(io->fd, buf, (size_t) size, io->pos);
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    io->pos += n;
    return (int) n;
}
static int64_t fd_seek(void *opaque, int64_t offset, int whence) {
    FdIo *io = opaque;
    if (whence & AVSEEK_SIZE) return io->size;
    whence &= ~AVSEEK_FORCE;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? io->pos : io->size;
    int64_t target = base + offset;
    if (target < 0) return AVERROR(EINVAL);
    io->pos = target;
    return target;
}

static AVFormatContext *open_input(int fd, const char *ext, FdIo **ioOut) {
    int dup_fd = dup(fd);
    if (dup_fd < 0) return NULL;
    struct stat st;
    FdIo *io = calloc(1, sizeof *io);
    io->fd = dup_fd;
    io->size = fstat(dup_fd, &st) == 0 ? st.st_size : -1;
    unsigned char *buffer = av_malloc(IO_BUFFER);
    AVIOContext *avio = avio_alloc_context(buffer, IO_BUFFER, 0, io, fd_read, NULL, io->size > 0 ? fd_seek : NULL);
    AVFormatContext *fmt = avformat_alloc_context();
    fmt->pb = avio;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    // A hint from the extension, so a file called .amr that is really 3gp is still found.
    char name[64] = "file";
    if (ext && *ext) snprintf(name, sizeof name, "file.%s", ext);
    if (avformat_open_input(&fmt, name, NULL, NULL) < 0) {
        // avformat_open_input frees fmt on failure, and with it our avio's buffer; not the opaque.
        close(dup_fd); free(io);
        return NULL;
    }
    if (avformat_find_stream_info(fmt, NULL) < 0) { avformat_close_input(&fmt); close(dup_fd); free(io); return NULL; }
    *ioOut = io;
    return fmt;
}

static void close_input(AVFormatContext *fmt, FdIo *io) {
    if (fmt) {
        AVIOContext *pb = fmt->pb;
        avformat_close_input(&fmt);
        if (pb) { av_freep(&pb->buffer); avio_context_free(&pb); }
    }
    if (io) { close(io->fd); free(io); }
}

/* ------------------------------------------------------------------ */
/* Probe                                                              */
/* ------------------------------------------------------------------ */

static void json_dict(Str *s, AVDictionary *d) {
    str_cat(s, "{");
    const AVDictionaryEntry *e = NULL; int first = 1;
    while ((e = av_dict_iterate(d, e))) {
        if (!first) str_cat(s, ",");
        first = 0;
        str_json(s, e->key); str_cat(s, ":"); str_json(s, e->value);
    }
    str_cat(s, "}");
}

JNIEXPORT jbyteArray JNICALL FN(probe)(JNIEnv *env, jclass cls, jint fd, jstring jext) {
    const char *ext = jext ? (*env)->GetStringUTFChars(env, jext, NULL) : NULL;
    FdIo *io = NULL;
    AVFormatContext *fmt = open_input(fd, ext, &io);
    if (ext) (*env)->ReleaseStringUTFChars(env, jext, ext);
    if (!fmt) return NULL;
    Str s = {0};
    str_cat(&s, "{");
    str_fmt(&s, "\"format\":\"%s\",", fmt->iformat->name);
    str_fmt(&s, "\"durationMs\":%lld,", fmt->duration > 0 ? (long long) (fmt->duration / 1000) : 0LL);
    str_fmt(&s, "\"bitrate\":%lld,", (long long) fmt->bit_rate);
    str_cat(&s, "\"tags\":"); json_dict(&s, fmt->metadata); str_cat(&s, ",");
    str_cat(&s, "\"streams\":[");
    int hasCover = 0;
    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        AVStream *st = fmt->streams[i];
        AVCodecParameters *p = st->codecpar;
        if (st->disposition & AV_DISPOSITION_ATTACHED_PIC) hasCover = 1;
        if (i) str_cat(&s, ",");
        const AVCodecDescriptor *d = avcodec_descriptor_get(p->codec_id);
        str_fmt(&s, "{\"index\":%u,\"type\":\"%s\",\"codec\":\"%s\",\"rate\":%d,\"channels\":%d,\"bitrate\":%lld,\"bits\":%d,\"frames\":%lld,\"attachedPic\":%s,\"tags\":",
                i, av_get_media_type_string(p->codec_type) ? av_get_media_type_string(p->codec_type) : "unknown",
                d ? d->name : "unknown", p->sample_rate, p->ch_layout.nb_channels, (long long) p->bit_rate,
                p->bits_per_raw_sample ? p->bits_per_raw_sample : p->bits_per_coded_sample,
                (long long) (st->duration > 0 && st->time_base.den ? av_rescale_q(st->duration, st->time_base, (AVRational){1, p->sample_rate > 0 ? p->sample_rate : 48000}) : 0),
                (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ? "true" : "false");
        json_dict(&s, st->metadata);
        str_cat(&s, "}");
    }
    str_cat(&s, "],\"chapters\":[");
    for (unsigned i = 0; i < fmt->nb_chapters; i++) {
        AVChapter *c = fmt->chapters[i];
        if (i) str_cat(&s, ",");
        const AVDictionaryEntry *t = av_dict_get(c->metadata, "title", NULL, 0);
        str_fmt(&s, "{\"startMs\":%lld,\"endMs\":%lld,\"title\":", (long long) av_rescale_q(c->start, c->time_base, (AVRational){1, 1000}), (long long) av_rescale_q(c->end, c->time_base, (AVRational){1, 1000}));
        str_json(&s, t ? t->value : ""); str_cat(&s, "}");
    }
    str_fmt(&s, "],\"hasCover\":%s}", hasCover ? "true" : "false");
    close_input(fmt, io);
    return json_result(env, &s);
}

JNIEXPORT jbyteArray JNICALL FN(coverArt)(JNIEnv *env, jclass cls, jint fd, jstring jext) {
    const char *ext = jext ? (*env)->GetStringUTFChars(env, jext, NULL) : NULL;
    FdIo *io = NULL;
    AVFormatContext *fmt = open_input(fd, ext, &io);
    if (ext) (*env)->ReleaseStringUTFChars(env, jext, ext);
    if (!fmt) return NULL;
    jbyteArray out = NULL;
    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        AVStream *st = fmt->streams[i];
        if ((st->disposition & AV_DISPOSITION_ATTACHED_PIC) && st->attached_pic.size > 0) {
            out = (*env)->NewByteArray(env, st->attached_pic.size);
            if (out) (*env)->SetByteArrayRegion(env, out, 0, st->attached_pic.size, (const jbyte *) st->attached_pic.data);
            break;
        }
    }
    close_input(fmt, io);
    return out;
}

/* ------------------------------------------------------------------ */
/* Decode: one stream to raw interleaved float                        */
/* ------------------------------------------------------------------ */

typedef struct {
    AVFormatContext *fmt; FdIo *io;
    int stream;
    AVCodecContext *dec;
    SwrContext *swr;
    int outRate, outChannels;
    volatile int progress;     /* permille */
    volatile int cancel;
    int64_t framesOut;
} Decoder;

JNIEXPORT jlong JNICALL FN(decOpen)(JNIEnv *env, jclass cls, jint fd, jstring jext, jint streamIndex) {
    const char *ext = jext ? (*env)->GetStringUTFChars(env, jext, NULL) : NULL;
    FdIo *io = NULL;
    AVFormatContext *fmt = open_input(fd, ext, &io);
    if (ext) (*env)->ReleaseStringUTFChars(env, jext, ext);
    if (!fmt) return 0;
    int stream = streamIndex;
    if (stream < 0 || stream >= (int) fmt->nb_streams || fmt->streams[stream]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO)
        stream = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream < 0) { close_input(fmt, io); return 0; }
    AVCodecParameters *par = fmt->streams[stream]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(par->codec_id);
    if (!codec) { close_input(fmt, io); return 0; }
    AVCodecContext *dec = avcodec_alloc_context3(codec);
    if (!dec || avcodec_parameters_to_context(dec, par) < 0) { if (dec) avcodec_free_context(&dec); close_input(fmt, io); return 0; }
    dec->pkt_timebase = fmt->streams[stream]->time_base;
    dec->thread_count = 2;
    if (avcodec_open2(dec, codec, NULL) < 0) { avcodec_free_context(&dec); close_input(fmt, io); return 0; }
    Decoder *d = calloc(1, sizeof *d);
    d->fmt = fmt; d->io = io; d->stream = stream; d->dec = dec;
    return (jlong) (intptr_t) d;
}

JNIEXPORT jlongArray JNICALL FN(decInfo)(JNIEnv *env, jclass cls, jlong h) {
    Decoder *d = (Decoder *) (intptr_t) h;
    jlong v[4];
    AVCodecParameters *p = d->fmt->streams[d->stream]->codecpar;
    v[0] = p->sample_rate; v[1] = p->ch_layout.nb_channels;
    v[2] = d->fmt->duration > 0 ? d->fmt->duration : 0;
    v[3] = d->fmt->duration > 0 && p->sample_rate > 0 ? av_rescale(d->fmt->duration, p->sample_rate, AV_TIME_BASE) : 0;
    jlongArray out = (*env)->NewLongArray(env, 4);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 4, v);
    return out;
}

static int write_frame_float(Decoder *d, AVFrame *frame, FILE *out, uint8_t **buf, int *bufFrames) {
    if (!d->swr) {
        AVChannelLayout outLayout;
        if (d->outChannels <= 0 || d->outChannels == frame->ch_layout.nb_channels) av_channel_layout_copy(&outLayout, &frame->ch_layout);
        else av_channel_layout_default(&outLayout, d->outChannels);
        d->outChannels = outLayout.nb_channels;
        if (d->outRate <= 0) d->outRate = frame->sample_rate;
        if (swr_alloc_set_opts2(&d->swr, &outLayout, AV_SAMPLE_FMT_FLT, d->outRate, &frame->ch_layout, (enum AVSampleFormat) frame->format, frame->sample_rate, 0, NULL) < 0) return -1;
        av_channel_layout_uninit(&outLayout);
        if (swr_init(d->swr) < 0) return -1;
    }
    int outCount = (int) av_rescale_rnd(swr_get_delay(d->swr, frame->sample_rate) + frame->nb_samples, d->outRate, frame->sample_rate, AV_ROUND_UP);
    if (outCount > *bufFrames) { av_freep(buf); *bufFrames = outCount; if (av_samples_alloc(buf, NULL, d->outChannels, outCount, AV_SAMPLE_FMT_FLT, 0) < 0) return -1; }
    int got = swr_convert(d->swr, buf, outCount, (const uint8_t **) frame->extended_data, frame->nb_samples);
    if (got < 0) return -1;
    if (got > 0) { fwrite(*buf, sizeof(float) * d->outChannels, got, out); d->framesOut += got; }
    return 0;
}

// Decodes the whole stream to [path] as interleaved float at [rate] (0 keeps the file's) and
// [channels] (0 keeps). Blocking; run on a worker. Returns frames written, or negative.
JNIEXPORT jlong JNICALL FN(decRun)(JNIEnv *env, jclass cls, jlong h, jstring jpath, jint rate, jint channels) {
    Decoder *d = (Decoder *) (intptr_t) h;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    FILE *out = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!out) return -1;
    d->outRate = rate; d->outChannels = channels; d->framesOut = 0;
    AVPacket *pkt = av_packet_alloc(); AVFrame *frame = av_frame_alloc();
    uint8_t *buf = NULL; int bufFrames = 0;
    int rc = 0;
    int64_t duration = d->fmt->duration;
    while (!d->cancel && (rc = av_read_frame(d->fmt, pkt)) >= 0) {
        if (pkt->stream_index == d->stream) {
            if (duration > 0 && pkt->pts != AV_NOPTS_VALUE) {
                int64_t t = av_rescale_q(pkt->pts, d->fmt->streams[d->stream]->time_base, AV_TIME_BASE_Q);
                int pm = (int) (t * 1000 / duration); d->progress = pm < 0 ? 0 : pm > 999 ? 999 : pm;
            }
            if (avcodec_send_packet(d->dec, pkt) >= 0) {
                while (avcodec_receive_frame(d->dec, frame) >= 0) { if (write_frame_float(d, frame, out, &buf, &bufFrames) < 0) { d->cancel = 2; break; } av_frame_unref(frame); }
            }
        }
        av_packet_unref(pkt);
    }
    if (!d->cancel) {
        // Drain: the frames the decoder holds for reordering come out after a NULL packet.
        avcodec_send_packet(d->dec, NULL);
        while (avcodec_receive_frame(d->dec, frame) >= 0) { write_frame_float(d, frame, out, &buf, &bufFrames); av_frame_unref(frame); }
        if (d->swr) {
            int got;
            do {
                if (bufFrames < 4096) { av_freep(&buf); bufFrames = 4096; av_samples_alloc(&buf, NULL, d->outChannels, bufFrames, AV_SAMPLE_FMT_FLT, 0); }
                got = swr_convert(d->swr, &buf, bufFrames, NULL, 0);
                if (got > 0) { fwrite(buf, sizeof(float) * d->outChannels, got, out); d->framesOut += got; }
            } while (got > 0);
        }
    }
    av_freep(&buf); av_packet_free(&pkt); av_frame_free(&frame);
    fclose(out);
    d->progress = 1000;
    if (d->cancel) return d->cancel == 2 ? -3 : -2;
    return d->framesOut;
}

JNIEXPORT jint JNICALL FN(decProgress)(JNIEnv *env, jclass cls, jlong h) { return ((Decoder *) (intptr_t) h)->progress; }
JNIEXPORT jint JNICALL FN(decOutRate)(JNIEnv *env, jclass cls, jlong h) { return ((Decoder *) (intptr_t) h)->outRate; }
JNIEXPORT jint JNICALL FN(decOutChannels)(JNIEnv *env, jclass cls, jlong h) { return ((Decoder *) (intptr_t) h)->outChannels; }
JNIEXPORT void JNICALL FN(decCancel)(JNIEnv *env, jclass cls, jlong h) { ((Decoder *) (intptr_t) h)->cancel = 1; }
JNIEXPORT void JNICALL FN(decClose)(JNIEnv *env, jclass cls, jlong h) {
    Decoder *d = (Decoder *) (intptr_t) h;
    if (!d) return;
    if (d->swr) swr_free(&d->swr);
    if (d->dec) avcodec_free_context(&d->dec);
    close_input(d->fmt, d->io);
    free(d);
}

/* ------------------------------------------------------------------ */
/* Encode: a raw float file to any encoder in the build                */
/* ------------------------------------------------------------------ */

typedef struct {
    char *inPath, *outPath, *format, *codec;
    int64_t frames; int rate, channels;
    int outRate, outChannels, bitrate; float quality;
    AVDictionary *tags;
    uint8_t *cover; int coverSize; char coverMime[32];
    struct { int64_t startMs, endMs; char *title; } *chapters; int chapterCount;
    volatile int progress, cancel;
} Encoder;

static char *dupstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = strdup(c ? c : "");
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

JNIEXPORT jlong JNICALL FN(encOpen)(JNIEnv *env, jclass cls, jstring inPath, jlong frames, jint rate, jint channels, jstring outPath, jstring format, jstring codec, jint bitrate, jint outRate, jint outChannels, jfloat quality) {
    Encoder *e = calloc(1, sizeof *e);
    e->inPath = dupstr(env, inPath); e->outPath = dupstr(env, outPath); e->format = dupstr(env, format); e->codec = dupstr(env, codec);
    e->frames = frames; e->rate = rate; e->channels = channels; e->bitrate = bitrate; e->outRate = outRate; e->outChannels = outChannels; e->quality = quality;
    return (jlong) (intptr_t) e;
}

JNIEXPORT void JNICALL FN(encSetTag)(JNIEnv *env, jclass cls, jlong h, jstring key, jstring value) {
    Encoder *e = (Encoder *) (intptr_t) h;
    char *k = dupstr(env, key), *v = dupstr(env, value);
    if (k && v) av_dict_set(&e->tags, k, v, 0);
    free(k); free(v);
}

JNIEXPORT void JNICALL FN(encSetCover)(JNIEnv *env, jclass cls, jlong h, jbyteArray bytes, jstring mime) {
    Encoder *e = (Encoder *) (intptr_t) h;
    free(e->cover); e->cover = NULL; e->coverSize = 0;
    if (!bytes) return;
    jsize n = (*env)->GetArrayLength(env, bytes);
    e->cover = malloc((size_t) n); e->coverSize = n;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *) e->cover);
    char *m = dupstr(env, mime);
    snprintf(e->coverMime, sizeof e->coverMime, "%s", m ? m : "image/jpeg");
    free(m);
}

JNIEXPORT void JNICALL FN(encAddChapter)(JNIEnv *env, jclass cls, jlong h, jlong startMs, jlong endMs, jstring title) {
    Encoder *e = (Encoder *) (intptr_t) h;
    e->chapters = realloc(e->chapters, sizeof *e->chapters * (e->chapterCount + 1));
    e->chapters[e->chapterCount].startMs = startMs; e->chapters[e->chapterCount].endMs = endMs; e->chapters[e->chapterCount].title = dupstr(env, title);
    e->chapterCount++;
}

static enum AVSampleFormat pick_format(const AVCodec *codec, AVCodecContext *ctx) {
    const enum AVSampleFormat *fmts = NULL; int n = 0;
    if (avcodec_get_supported_config(ctx, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0, (const void **) &fmts, &n) < 0 || !fmts) return AV_SAMPLE_FMT_FLTP;
    // Prefer float, then 32-bit int, then 16-bit: the order that loses the least from a float input.
    enum AVSampleFormat order[] = {AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_S32P, AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S16P, AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_DBLP, AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_U8P, AV_SAMPLE_FMT_U8};
    for (size_t o = 0; o < sizeof order / sizeof order[0]; o++) for (int i = 0; i < n && fmts[i] != AV_SAMPLE_FMT_NONE; i++) if (fmts[i] == order[o]) return order[o];
    return fmts[0];
}

static int pick_rate(const AVCodec *codec, AVCodecContext *ctx, int wanted) {
    const int *rates = NULL; int n = 0;
    if (avcodec_get_supported_config(ctx, codec, AV_CODEC_CONFIG_SAMPLE_RATE, 0, (const void **) &rates, &n) < 0 || !rates || n == 0) return wanted;
    int best = rates[0];
    for (int i = 0; i < n && rates[i]; i++) { if (rates[i] == wanted) return wanted; if (abs(rates[i] - wanted) < abs(best - wanted)) best = rates[i]; }
    return best;
}

static int write_encoded(AVFormatContext *out, AVCodecContext *enc, AVStream *st, AVFrame *frame) {
    int rc = avcodec_send_frame(enc, frame);
    if (rc < 0) return rc;
    AVPacket *pkt = av_packet_alloc();
    while ((rc = avcodec_receive_packet(enc, pkt)) >= 0) {
        pkt->stream_index = st->index;
        av_packet_rescale_ts(pkt, enc->time_base, st->time_base);
        rc = av_interleaved_write_frame(out, pkt);
        av_packet_unref(pkt);
        if (rc < 0) break;
    }
    av_packet_free(&pkt);
    return rc == AVERROR(EAGAIN) || rc == AVERROR_EOF ? 0 : rc;
}

// Runs the encode. Blocking; run on a worker. 0 on success, negative on failure.
JNIEXPORT jint JNICALL FN(encRun)(JNIEnv *env, jclass cls, jlong h) {
    Encoder *e = (Encoder *) (intptr_t) h;
    int rc = -1;
    AVFormatContext *out = NULL;
    AVCodecContext *enc = NULL;
    SwrContext *swr = NULL;
    AVAudioFifo *fifo = NULL;
    FILE *in = fopen(e->inPath, "rb");
    if (!in) return -1;
    if (avformat_alloc_output_context2(&out, NULL, e->format && *e->format ? e->format : NULL, e->outPath) < 0 || !out) { fclose(in); return -2; }
    const AVCodec *codec = e->codec && *e->codec ? avcodec_find_encoder_by_name(e->codec) : avcodec_find_encoder(out->oformat->audio_codec);
    if (!codec) { rc = -3; goto done; }
    AVStream *st = avformat_new_stream(out, NULL);
    enc = avcodec_alloc_context3(codec);
    enc->sample_fmt = pick_format(codec, enc);
    enc->sample_rate = pick_rate(codec, enc, e->outRate > 0 ? e->outRate : e->rate);
    av_channel_layout_default(&enc->ch_layout, e->outChannels > 0 ? e->outChannels : e->channels);
    if (e->bitrate > 0) enc->bit_rate = e->bitrate;
    if (e->quality >= 0 && e->bitrate <= 0) { enc->flags |= AV_CODEC_FLAG_QSCALE; enc->global_quality = (int) (e->quality * FF_QP2LAMBDA); }
    enc->time_base = (AVRational){1, enc->sample_rate};
    if (out->oformat->flags & AVFMT_GLOBALHEADER) enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (avcodec_open2(enc, codec, NULL) < 0) { rc = -4; goto done; }
    avcodec_parameters_from_context(st->codecpar, enc);
    st->time_base = enc->time_base;
    // Tags.
    av_dict_copy(&out->metadata, e->tags, 0);
    // Chapters.
    for (int i = 0; i < e->chapterCount; i++) {
        AVChapter *c = av_mallocz(sizeof *c);
        c->id = i + 1; c->time_base = (AVRational){1, 1000}; c->start = e->chapters[i].startMs; c->end = e->chapters[i].endMs;
        av_dict_set(&c->metadata, "title", e->chapters[i].title ? e->chapters[i].title : "", 0);
        av_dynarray_add(&out->chapters, (int *) &out->nb_chapters, c);
    }
    // Cover art as an attached picture stream, where the format carries one (MP3, MP4/M4A, FLAC).
    AVStream *pic = NULL;
    if (e->cover && e->coverSize > 0 && !(out->oformat->flags & AVFMT_NOTIMESTAMPS) ) {
        pic = avformat_new_stream(out, NULL);
        pic->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
        pic->codecpar->codec_id = strstr(e->coverMime, "png") ? AV_CODEC_ID_PNG : AV_CODEC_ID_MJPEG;
        pic->disposition |= AV_DISPOSITION_ATTACHED_PIC;
        pic->time_base = (AVRational){1, 90000};
        av_dict_set(&pic->metadata, "comment", "Cover (front)", 0);
        av_dict_set(&pic->metadata, "title", "Album cover", 0);
    }
    if (avio_open(&out->pb, e->outPath, AVIO_FLAG_WRITE) < 0) { rc = -5; goto done; }
    if (avformat_write_header(out, NULL) < 0) { rc = -6; goto done; }
    if (pic) {
        AVPacket *pp = av_packet_alloc();
        av_new_packet(pp, e->coverSize);
        memcpy(pp->data, e->cover, e->coverSize);
        pp->stream_index = pic->index; pp->flags |= AV_PKT_FLAG_KEY; pp->pts = pp->dts = 0;
        av_interleaved_write_frame(out, pp);
        av_packet_free(&pp);
    }
    // Resample float interleaved input to the encoder's format and rate.
    AVChannelLayout inLayout; av_channel_layout_default(&inLayout, e->channels);
    if (swr_alloc_set_opts2(&swr, &enc->ch_layout, enc->sample_fmt, enc->sample_rate, &inLayout, AV_SAMPLE_FMT_FLT, e->rate, 0, NULL) < 0 || swr_init(swr) < 0) { rc = -7; goto done; }
    av_channel_layout_uninit(&inLayout);
    int frameSize = enc->frame_size > 0 ? enc->frame_size : 4096;
    fifo = av_audio_fifo_alloc(enc->sample_fmt, enc->ch_layout.nb_channels, frameSize * 4);
    AVFrame *outFrame = av_frame_alloc();
    outFrame->format = enc->sample_fmt; outFrame->nb_samples = frameSize; av_channel_layout_copy(&outFrame->ch_layout, &enc->ch_layout); outFrame->sample_rate = enc->sample_rate;
    av_frame_get_buffer(outFrame, 0);
    const int chunk = 8192;
    float *inBuf = malloc(sizeof(float) * chunk * e->channels);
    uint8_t **conv = NULL; int convFrames = 0;
    int64_t pts = 0, readFrames = 0;
    size_t got;
    while (!e->cancel && (got = fread(inBuf, sizeof(float) * e->channels, chunk, in)) > 0) {
        readFrames += (int64_t) got;
        if (e->frames > 0) e->progress = (int) (readFrames * 1000 / e->frames);
        int outCount = (int) av_rescale_rnd(swr_get_delay(swr, e->rate) + (int64_t) got, enc->sample_rate, e->rate, AV_ROUND_UP);
        if (outCount > convFrames) { if (conv) { av_freep(&conv[0]); av_freep(&conv); } convFrames = outCount; av_samples_alloc_array_and_samples(&conv, NULL, enc->ch_layout.nb_channels, outCount, enc->sample_fmt, 0); }
        const uint8_t *inPlanes[1] = {(const uint8_t *) inBuf};
        int n = swr_convert(swr, conv, outCount, inPlanes, (int) got);
        if (n > 0) av_audio_fifo_write(fifo, (void **) conv, n);
        while (av_audio_fifo_size(fifo) >= frameSize) {
            av_frame_make_writable(outFrame);
            av_audio_fifo_read(fifo, (void **) outFrame->data, frameSize);
            outFrame->nb_samples = frameSize; outFrame->pts = pts; pts += frameSize;
            if (write_encoded(out, enc, st, outFrame) < 0) { e->cancel = 2; break; }
        }
    }
    if (!e->cancel) {
        // Flush the resampler and the fifo, then the encoder.
        int n;
        do {
            if (convFrames < 4096) { if (conv) { av_freep(&conv[0]); av_freep(&conv); } convFrames = 4096; av_samples_alloc_array_and_samples(&conv, NULL, enc->ch_layout.nb_channels, convFrames, enc->sample_fmt, 0); }
            n = swr_convert(swr, conv, convFrames, NULL, 0);
            if (n > 0) av_audio_fifo_write(fifo, (void **) conv, n);
        } while (n > 0);
        while (av_audio_fifo_size(fifo) > 0) {
            int take = av_audio_fifo_size(fifo) < frameSize ? av_audio_fifo_size(fifo) : frameSize;
            av_frame_make_writable(outFrame);
            av_audio_fifo_read(fifo, (void **) outFrame->data, take);
            outFrame->nb_samples = take; outFrame->pts = pts; pts += take;
            if (write_encoded(out, enc, st, outFrame) < 0) break;
            if (!(codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) && take < frameSize) break;
        }
        write_encoded(out, enc, st, NULL);
        av_write_trailer(out);
        rc = 0;
    } else rc = e->cancel == 2 ? -8 : -9;
    if (conv) { av_freep(&conv[0]); av_freep(&conv); }
    free(inBuf);
    av_frame_free(&outFrame);
done:
    if (fifo) av_audio_fifo_free(fifo);
    if (swr) swr_free(&swr);
    if (enc) avcodec_free_context(&enc);
    if (out) { if (out->pb) avio_closep(&out->pb); avformat_free_context(out); }
    fclose(in);
    e->progress = 1000;
    if (rc != 0) unlink(e->outPath);
    return rc;
}

JNIEXPORT jint JNICALL FN(encProgress)(JNIEnv *env, jclass cls, jlong h) { return ((Encoder *) (intptr_t) h)->progress; }
JNIEXPORT void JNICALL FN(encCancel)(JNIEnv *env, jclass cls, jlong h) { ((Encoder *) (intptr_t) h)->cancel = 1; }
JNIEXPORT void JNICALL FN(encClose)(JNIEnv *env, jclass cls, jlong h) {
    Encoder *e = (Encoder *) (intptr_t) h;
    if (!e) return;
    free(e->inPath); free(e->outPath); free(e->format); free(e->codec); free(e->cover);
    for (int i = 0; i < e->chapterCount; i++) free(e->chapters[i].title);
    free(e->chapters);
    av_dict_free(&e->tags);
    free(e);
}

/* ------------------------------------------------------------------ */
/* About the library                                                   */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL FN(version)(JNIEnv *env, jclass cls) {
    char buf[128];
    snprintf(buf, sizeof buf, "FFmpeg %s (avcodec %u.%u.%u)", av_version_info(), avcodec_version() >> 16, (avcodec_version() >> 8) & 255, avcodec_version() & 255);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jboolean JNICALL FN(hasEncoder)(JNIEnv *env, jclass cls, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    jboolean has = avcodec_find_encoder_by_name(name) != NULL;
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return has;
}

JNIEXPORT jboolean JNICALL FN(hasDecoder)(JNIEnv *env, jclass cls, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    jboolean has = avcodec_find_decoder_by_name(name) != NULL;
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return has;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    av_log_set_level(AV_LOG_ERROR);
    return JNI_VERSION_1_6;
}
