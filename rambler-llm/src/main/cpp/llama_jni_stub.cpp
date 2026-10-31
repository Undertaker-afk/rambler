// Used on ABIs llama.cpp is not built for (32-bit). The model simply reports "failed to load"
// and Rambler falls back to the rule-based cleaner.
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeLoad(JNIEnv *, jobject, jstring, jint) { return 0; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeGenerate(JNIEnv *, jobject, jlong, jstring, jstring, jint) { return nullptr; }

extern "C" JNIEXPORT void JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong) {}
