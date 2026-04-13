#include <jni.h>
#include <stdint.h>

/**
 * Wraps a native memory address as a java.nio.DirectByteBuffer using the
 * officially supported JNI function NewDirectByteBuffer.
 *
 * This replaces the reflection-based approach (DirectByteBuffer(long, int)
 * constructor) which is blocklisted on Android API 28+.
 */
JNIEXPORT jobject JNICALL
Java_com_ditchoom_buffer_NativeBufferHelper_newDirectByteBuffer(
    JNIEnv *env, jclass clazz, jlong address, jint capacity) {
    return (*env)->NewDirectByteBuffer(env, (void *)(intptr_t)address, (jlong)capacity);
}

/**
 * Returns the native memory address of a DirectByteBuffer using the
 * officially supported JNI function GetDirectBufferAddress.
 *
 * This replaces the reflection-based approach (reading Buffer.address field)
 * which may be blocklisted on future Android versions.
 */
JNIEXPORT jlong JNICALL
Java_com_ditchoom_buffer_NativeBufferHelper_getDirectBufferAddress(
    JNIEnv *env, jclass clazz, jobject buffer) {
    void *addr = (*env)->GetDirectBufferAddress(env, buffer);
    return addr ? (jlong)(intptr_t)addr : 0L;
}
