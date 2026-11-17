// Small helpers shared by the JNI bridges.
#pragma once

#include <jni.h>

#include <vector>

namespace chordify {

inline void throwJava(JNIEnv *env, const char *className, const char *message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) env->ThrowNew(type, message);
}

inline jfloatArray toJava(JNIEnv *env, const std::vector<float> &values) {
    jfloatArray array = env->NewFloatArray(jsize(values.size()));
    if (array != nullptr) env->SetFloatArrayRegion(array, 0, jsize(values.size()), values.data());
    return array;
}

}
