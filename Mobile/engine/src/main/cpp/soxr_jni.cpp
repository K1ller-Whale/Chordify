// JNI bridge for com.arbaba.chordifymobile.engine.audio.SoxrNative: libsoxr set up as
// python-soxr 1.1.0's soxr.resample sets it up for librosa.resample(res_type="soxr_hq"):
// float32 in and out, SOXR_HQ, no flags, default runtime settings.
#include <jni.h>

#include <vector>

#include "jni_util.h"
#include "soxr.h"

using chordify::throwJava;
using chordify::toJava;

namespace {

// Runs soxr_process until all `count` samples are consumed (or, with in == nullptr, until the
// flush yields nothing more) and returns everything it produced.
std::vector<float> run(JNIEnv *env, soxr_t soxr, const float *in, std::size_t count) {
    std::vector<float> out;
    float buffer[8192];
    std::size_t consumed = 0;
    for (;;) {
        std::size_t used = 0, made = 0;
        soxr_error_t error = soxr_process(soxr, in == nullptr ? nullptr : in + consumed, count - consumed,
                                          in == nullptr ? nullptr : &used, buffer, 8192, &made);
        if (error != nullptr) {
            throwJava(env, "java/lang/IllegalStateException", error);
            return {};
        }
        consumed += used;
        out.insert(out.end(), buffer, buffer + made);
        if (in == nullptr ? made == 0 : (consumed == count && made < 8192)) return out;
    }
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_create(JNIEnv *env, jclass, jdouble inRate,
                                                              jdouble outRate) {
    soxr_error_t error = nullptr;
    soxr_io_spec_t io = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
    soxr_quality_spec_t quality = soxr_quality_spec(SOXR_HQ, 0);
    soxr_t soxr = soxr_create(inRate, outRate, 1, &error, &io, &quality, nullptr);
    if (error != nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", error);
        return 0;
    }
    return reinterpret_cast<jlong>(soxr);
}

JNIEXPORT jfloatArray JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_process(JNIEnv *env, jclass, jlong handle,
                                                               jfloatArray input, jint count) {
    std::vector<float> in(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(input, 0, count, in.data());
    std::vector<float> out = run(env, reinterpret_cast<soxr_t>(handle), in.data(), in.size());
    return env->ExceptionCheck() ? nullptr : toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_flush(JNIEnv *env, jclass, jlong handle) {
    std::vector<float> out = run(env, reinterpret_cast<soxr_t>(handle), nullptr, 0);
    return env->ExceptionCheck() ? nullptr : toJava(env, out);
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_release(JNIEnv *, jclass, jlong handle) {
    soxr_delete(reinterpret_cast<soxr_t>(handle));
}

}
