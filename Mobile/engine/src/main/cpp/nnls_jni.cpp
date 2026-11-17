// JNI bridge for com.arbaba.chordifymobile.engine.features.NnlsNative.
#include <jni.h>

#include <exception>
#include <string>

#include "jni_util.h"
#include "nnls_host.h"

using chordify::NnlsHost;
using chordify::throwJava;
using chordify::toJava;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_create(JNIEnv *env, jclass, jfloat sampleRate,
                                                                 jstring output) {
    const char *chars = env->GetStringUTFChars(output, nullptr);
    std::string name(chars);
    env->ReleaseStringUTFChars(output, chars);
    try {
        return reinterpret_cast<jlong>(new NnlsHost(sampleRate, name));
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_write(JNIEnv *env, jclass, jlong handle,
                                                                jfloatArray samples, jint count) {
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    try {
        reinterpret_cast<NnlsHost *>(handle)->write(data, std::size_t(count));
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT jobjectArray JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_finish(JNIEnv *env, jclass, jlong handle) {
    std::vector<std::vector<float>> rows;
    try {
        rows = reinterpret_cast<NnlsHost *>(handle)->finish();
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(jsize(rows.size()), env->FindClass("[F"), nullptr);
    for (std::size_t i = 0; i < rows.size(); ++i) {
        jfloatArray row = toJava(env, rows[i]);
        env->SetObjectArrayElement(result, jsize(i), row);
        env->DeleteLocalRef(row);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_release(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<NnlsHost *>(handle);
}

}
