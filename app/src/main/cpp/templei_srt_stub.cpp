#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeConnect(
        JNIEnv*,
        jobject,
        jstring,
        jint,
        jint,
        jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStartSending(
        JNIEnv*,
        jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeSendPacket(
        JNIEnv*,
        jobject,
        jbyteArray) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStopSending(
        JNIEnv*,
        jobject) {
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeLastError(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF("");
}
