// libstavewhisper.so: whisper.cpp (MIT) behind a small JNI surface, for the transcript. Built by
// tools/native/build_whisper.sh on GitHub's runners and committed under app/src/main/jniLibs.
// Carried from Squish, which learned two things the hard way: words, not sentences (max_len 1
// with split_on_word gives each word its own times), and text as *bytes*, because
// NewStringUTF aborts on a character cut across two of whisper.cpp's pieces, which any Indic
// script does.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

static void quiet_log(enum ggml_log_level, const char*, void*) {}

// The alignment heads DTW reads, by the model's file name. Without DTW a word's time is
// whisper's timestamp-token guess: on the phone (1 Nov 2026) "lazy dog." was placed 0.3 s late
// and a sentence's last word ran on through the pause after it, so tapping two words selected
// mostly silence. An unknown file gets the generic heads of the top two text layers.
static whisper_alignment_heads_preset aheadsFor(const std::string& path) {
    std::string name = path.substr(path.find_last_of('/') + 1);
    bool en = name.find(".en") != std::string::npos;
    if (name.find("large-v3-turbo") != std::string::npos) return WHISPER_AHEADS_LARGE_V3_TURBO;
    if (name.find("large-v3") != std::string::npos) return WHISPER_AHEADS_LARGE_V3;
    if (name.find("large-v2") != std::string::npos) return WHISPER_AHEADS_LARGE_V2;
    if (name.find("medium") != std::string::npos) return en ? WHISPER_AHEADS_MEDIUM_EN : WHISPER_AHEADS_MEDIUM;
    if (name.find("small") != std::string::npos) return en ? WHISPER_AHEADS_SMALL_EN : WHISPER_AHEADS_SMALL;
    if (name.find("base") != std::string::npos) return en ? WHISPER_AHEADS_BASE_EN : WHISPER_AHEADS_BASE;
    if (name.find("tiny") != std::string::npos) return en ? WHISPER_AHEADS_TINY_EN : WHISPER_AHEADS_TINY;
    return WHISPER_AHEADS_N_TOP_MOST;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_layerbit_stave_native_WhisperNative_open(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    whisper_log_set(quiet_log, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;           // DTW reads the attention weights flash attention never forms
    cp.dtw_token_timestamps = true;
    cp.dtw_aheads_preset = aheadsFor(path);
    cp.dtw_n_top = 2;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_layerbit_stave_native_WhisperNative_close(JNIEnv*, jclass, jlong h) {
    if (h != 0) whisper_free(reinterpret_cast<whisper_context*>(h));
}

// Transcribes [pcm] (16 kHz mono floats). [lang] is a code ("en", "hi", "ml") or "auto". The
// result is one line per word, "t0ms\tt1ms\ttext", then a last line "lang\t<code>", as bytes.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_layerbit_stave_native_WhisperNative_transcribeBytes(JNIEnv* env, jclass, jlong h, jfloatArray jpcm, jstring jlang, jint threads, jstring jprompt) {
    auto* ctx = reinterpret_cast<whisper_context*>(h);
    if (ctx == nullptr) return nullptr;
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);
    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm((size_t) n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = language.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.suppress_blank = true;
    std::string prompt;
    if (jprompt != nullptr) { const char* pr = env->GetStringUTFChars(jprompt, nullptr); prompt = pr ? pr : ""; env->ReleaseStringUTFChars(jprompt, pr); }
    if (!prompt.empty()) p.initial_prompt = prompt.c_str();

    if (whisper_full(ctx, p, pcm.data(), n) != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        std::string word(text);
        size_t a = word.find_first_not_of(" \t\r\n");
        if (a == std::string::npos) continue;
        size_t b = word.find_last_not_of(" \t\r\n");
        word = word.substr(a, b - a + 1);
        for (char& c : word) if (c == '\t' || c == '\n') c = ' ';
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        // The word starts where DTW hears its first text token (special tokens carry no sound).
        const whisper_token eot = whisper_token_eot(ctx);
        for (int k = 0; k < whisper_full_n_tokens(ctx, i); k++) {
            const whisper_token_data td = whisper_full_get_token_data(ctx, i, k);
            if (td.id >= eot) continue;
            if (td.t_dtw >= 0) { t0 = td.t_dtw * 10; if (t1 < t0) t1 = t0; }
            break;
        }
        out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + word + "\n";
    }
    const int id = whisper_full_lang_id(ctx);
    out += std::string("lang\t") + (id >= 0 ? whisper_lang_str(id) : "") + "\n";
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return bytes;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_layerbit_stave_native_WhisperNative_version(JNIEnv* env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}
