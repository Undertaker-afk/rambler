// JNI bridge over rambler::Engine (see engine.cpp).
#include <jni.h>

#include <string>

#include "engine.h"

namespace {
std::string to_std(JNIEnv *env, jstring js) {
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint threads) {
    std::string path = to_std(env, jpath);
    return reinterpret_cast<jlong>(rambler::load(path.c_str(), threads));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprefix,
                                                             jstring jsuffix, jint max_tokens) {
    auto *e = reinterpret_cast<rambler::Engine *>(handle);
    if (!e) return nullptr;
    std::string out;
    if (!rambler::generate(e, to_std(env, jprefix), to_std(env, jsuffix), max_tokens, out)) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_futo_inputmethod_rambler_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    rambler::release(reinterpret_cast<rambler::Engine *>(handle));
}
