// The JNI surface of libstave.so: EngineNative (the graph), StretchNative (Rubber Band offline)
// and SoundFontNative (TinySoundFont). Handles are pointers as longs; 0 is failure.
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "../graph/Engine.h"
#include "../graph/SoundFont.h"
#include "../graph/Stretch.h"

using stave::Engine;

#define ENGINE(h) reinterpret_cast<Engine*>(h)
#define FN(name) Java_com_layerbit_stave_native_EngineNative_##name

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL FN(create)(JNIEnv*, jclass, jint sampleRate) { return reinterpret_cast<jlong>(new Engine(sampleRate)); }
JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jclass, jlong h) { delete ENGINE(h); }
JNIEXPORT jboolean JNICALL FN(start)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->start(); }
JNIEXPORT void JNICALL FN(stop)(JNIEnv*, jclass, jlong h) { ENGINE(h)->stop(); }
JNIEXPORT jint JNICALL FN(sampleRate)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->sampleRate(); }
JNIEXPORT jint JNICALL FN(framesPerBurst)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->framesPerBurst(); }
JNIEXPORT jdouble JNICALL FN(outputLatencyMs)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->outputLatencyMs(); }
JNIEXPORT jdouble JNICALL FN(inputLatencyMs)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->inputLatencyMs(); }
JNIEXPORT jint JNICALL FN(xruns)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->xruns(); }

JNIEXPORT jboolean JNICALL FN(setSnapshot)(JNIEnv* env, jclass, jlong h, jbyteArray bytes) {
    jsize n = env->GetArrayLength(bytes);
    std::vector<uint8_t> buf((size_t) n);
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    return ENGINE(h)->setSnapshot(buf.data(), buf.size());
}

JNIEXPORT void JNICALL FN(play)(JNIEnv*, jclass, jlong h) { ENGINE(h)->play(); }
JNIEXPORT void JNICALL FN(pause)(JNIEnv*, jclass, jlong h) { ENGINE(h)->pause(); }
JNIEXPORT jboolean JNICALL FN(isPlaying)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->isPlaying(); }
JNIEXPORT void JNICALL FN(seek)(JNIEnv*, jclass, jlong h, jlong frame) { ENGINE(h)->seek(frame); }
JNIEXPORT jlong JNICALL FN(position)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->getPosition(); }
JNIEXPORT void JNICALL FN(setLoop)(JNIEnv*, jclass, jlong h, jlong start, jlong end, jboolean on) { ENGINE(h)->setLoop(start, end, on); }
JNIEXPORT void JNICALL FN(setPlayEnd)(JNIEnv*, jclass, jlong h, jlong end) { ENGINE(h)->setPlayEnd(end); }
JNIEXPORT void JNICALL FN(captureStart)(JNIEnv*, jclass, jlong h, jint seconds) { ENGINE(h)->captureStart(seconds); }
// Stops the debug capture and writes it to [path] as raw interleaved stereo floats; returns frames.
JNIEXPORT jlong JNICALL FN(captureSave)(JNIEnv* env, jclass, jlong h, jstring path) {
    size_t n = ENGINE(h)->captureStop();
    const char* p = env->GetStringUTFChars(path, nullptr);
    FILE* f = std::fopen(p, "wb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return -1;
    std::fwrite(ENGINE(h)->captureData(), sizeof(float), n, f);
    std::fclose(f);
    return (jlong) (n / 2);
}
JNIEXPORT void JNICALL FN(setStopAtEnd)(JNIEnv*, jclass, jlong h, jboolean on) { ENGINE(h)->setStopAtEnd(on); }

JNIEXPORT void JNICALL FN(setTrackVolume)(JNIEnv*, jclass, jlong h, jint track, jfloat gain) { ENGINE(h)->setTrackVolume(track, gain); }
JNIEXPORT void JNICALL FN(setTrackPan)(JNIEnv*, jclass, jlong h, jint track, jfloat pan) { ENGINE(h)->setTrackPan(track, pan); }
JNIEXPORT void JNICALL FN(setMasterVolume)(JNIEnv*, jclass, jlong h, jfloat gain) { ENGINE(h)->setMasterVolume(gain); }
JNIEXPORT void JNICALL FN(clearOverrides)(JNIEnv*, jclass, jlong h) { ENGINE(h)->clearOverrides(); }

JNIEXPORT jint JNICALL FN(readMeters)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    jsize cap = env->GetArrayLength(out);
    std::vector<float> tmp((size_t) cap);
    int n = ENGINE(h)->readMeters(tmp.data(), cap);
    env->SetFloatArrayRegion(out, 0, n, tmp.data());
    return n;
}

JNIEXPORT jboolean JNICALL FN(openInput)(JNIEnv*, jclass, jlong h, jint channels, jint preset, jint deviceId) { return ENGINE(h)->openInput(channels, preset, deviceId); }
JNIEXPORT void JNICALL FN(closeInput)(JNIEnv*, jclass, jlong h) { ENGINE(h)->closeInput(); }
JNIEXPORT void JNICALL FN(setMonitor)(JNIEnv*, jclass, jlong h, jboolean on, jfloat gain) { ENGINE(h)->setMonitor(on, gain); }
JNIEXPORT jboolean JNICALL FN(startRecording)(JNIEnv* env, jclass, jlong h, jstring path) { return ENGINE(h)->startRecording(jstr(env, path)); }
JNIEXPORT void JNICALL FN(stopRecording)(JNIEnv*, jclass, jlong h) { ENGINE(h)->stopRecording(); }
JNIEXPORT jboolean JNICALL FN(isRecording)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->recording(); }
JNIEXPORT jlong JNICALL FN(recordedFrames)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->recordedFrames(); }
JNIEXPORT jlong JNICALL FN(droppedFrames)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->droppedFrames(); }
JNIEXPORT jfloat JNICALL FN(inputPeak)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->inputPeak(); }
JNIEXPORT jint JNICALL FN(readInputTap)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    jsize cap = env->GetArrayLength(out);
    std::vector<float> tmp((size_t) cap);
    int n = ENGINE(h)->readInputTap(tmp.data(), cap);
    env->SetFloatArrayRegion(out, 0, n, tmp.data());
    return n;
}
JNIEXPORT void JNICALL FN(setMetronome)(JNIEnv*, jclass, jlong h, jboolean on, jdouble bpm, jint beats, jfloat gain) { ENGINE(h)->setMetronome(on, bpm, beats, gain); }
JNIEXPORT void JNICALL FN(setLiveTune)(JNIEnv*, jclass, jlong h, jboolean on, jint root, jint mask, jfloat strength, jfloat retuneMs, jboolean recordTuned) { ENGINE(h)->setLiveTune(on, root, mask, strength, retuneMs, recordTuned); }
JNIEXPORT jfloat JNICALL FN(liveTuneHz)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->liveTuneHz(); }
JNIEXPORT jfloat JNICALL FN(liveTuneTargetHz)(JNIEnv*, jclass, jlong h) { return ENGINE(h)->liveTuneTargetHz(); }
JNIEXPORT void JNICALL FN(setRecordPaused)(JNIEnv*, jclass, jlong h, jboolean paused) { ENGINE(h)->setRecordPaused(paused); }
JNIEXPORT void JNICALL FN(scheduleClick)(JNIEnv*, jclass, jlong h, jlong at) { ENGINE(h)->scheduleClick(at); }

// Renders [from, to) of the current snapshot into a raw interleaved stereo float file at [path].
JNIEXPORT jlong JNICALL FN(renderOffline)(JNIEnv* env, jclass, jlong h, jlong from, jlong to, jstring path) {
    std::string p = jstr(env, path);
    FILE* f = fopen(p.c_str(), "wb");
    if (!f) return -1;
    // Written as it is rendered, a block at a time (Engine::renderOffline says why).
    int64_t n = ENGINE(h)->renderOffline(from, to, [f](const float* block, int frames) {
        return fwrite(block, sizeof(float), (size_t) frames * 2, f) == (size_t) frames * 2;
    });
    fclose(f);
    return n < 0 ? -2 : n;
}

// ---- StretchNative ---------------------------------------------------------------------------

JNIEXPORT jfloatArray JNICALL Java_com_layerbit_stave_native_StretchNative_stretch(JNIEnv* env, jclass, jfloatArray in, jint channels, jint sampleRate, jdouble ratio, jdouble semitones, jboolean formant) {
    jsize n = env->GetArrayLength(in);
    std::vector<float> buf((size_t) n);
    env->GetFloatArrayRegion(in, 0, n, buf.data());
    std::vector<float> out = stave::stretchOffline(buf.data(), n / channels, channels, sampleRate, ratio, semitones, formant);
    jfloatArray result = env->NewFloatArray((jsize) out.size());
    if (result) env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return result;
}

// ---- SoundFontNative -------------------------------------------------------------------------

#define SF(h) reinterpret_cast<stave::SoundFont*>(h)
JNIEXPORT jlong JNICALL Java_com_layerbit_stave_native_SoundFontNative_open(JNIEnv* env, jclass, jstring path, jint rate) {
    auto* sf = new stave::SoundFont();
    if (!sf->load(jstr(env, path), rate)) { delete sf; return 0; }
    return reinterpret_cast<jlong>(sf);
}
JNIEXPORT void JNICALL Java_com_layerbit_stave_native_SoundFontNative_close(JNIEnv*, jclass, jlong h) { delete SF(h); }
JNIEXPORT jint JNICALL Java_com_layerbit_stave_native_SoundFontNative_presetCount(JNIEnv*, jclass, jlong h) { return SF(h)->presetCount(); }
JNIEXPORT jstring JNICALL Java_com_layerbit_stave_native_SoundFontNative_presetName(JNIEnv* env, jclass, jlong h, jint i) { return env->NewStringUTF(SF(h)->presetName(i).c_str()); }
JNIEXPORT void JNICALL Java_com_layerbit_stave_native_SoundFontNative_noteOn(JNIEnv*, jclass, jlong h, jint preset, jint key, jfloat vel) { SF(h)->noteOn(preset, key, vel); }
JNIEXPORT void JNICALL Java_com_layerbit_stave_native_SoundFontNative_noteOff(JNIEnv*, jclass, jlong h, jint preset, jint key) { SF(h)->noteOff(preset, key); }
JNIEXPORT void JNICALL Java_com_layerbit_stave_native_SoundFontNative_allOff(JNIEnv*, jclass, jlong h) { SF(h)->allOff(); }
JNIEXPORT void JNICALL Java_com_layerbit_stave_native_SoundFontNative_render(JNIEnv* env, jclass, jlong h, jfloatArray out, jint frames) {
    std::vector<float> buf((size_t) frames * 2);
    SF(h)->render(buf.data(), frames);
    env->SetFloatArrayRegion(out, 0, frames * 2, buf.data());
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

} // extern "C"
