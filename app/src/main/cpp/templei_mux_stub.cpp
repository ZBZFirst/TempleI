#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativePrepare(
        JNIEnv*,
        jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeStart(
        JNIEnv*,
        jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeStop(
        JNIEnv*,
        jobject) {
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeIngestVideo(
        JNIEnv*,
        jobject,
        jbyteArray,
        jlong,
        jint,
        jint) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeIngestAudio(
        JNIEnv*,
        jobject,
        jbyteArray,
        jlong,
        jint,
        jint) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeDrainPacket(
        JNIEnv* env,
        jobject) {
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeLastError(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF("");
}
