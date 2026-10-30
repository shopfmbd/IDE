#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java___PKGJNI___MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
    std::string s = "Hello from C++ (NDK)";
    return env->NewStringUTF(s.c_str());
}
