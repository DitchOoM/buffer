// JNI shim for wrapping a raw native address as a java.nio.DirectByteBuffer
// without going through Android's hidden-API-restricted reflective constructor
// `java.nio.DirectByteBuffer.<init>(long, int)`.
//
// env->NewDirectByteBuffer is a public JNI function that's been part of the
// spec since JNI 1.4 and is not subject to Android's non-SDK enforcement.

#include <jni.h>

extern "C" JNIEXPORT jobject JNICALL
Java_com_ditchoom_buffer_JniDirectByteBufferAllocator_newDirectByteBuffer(
        JNIEnv *env,
        jclass /* cls */,
        jlong address,
        jint capacity) {
    return env->NewDirectByteBuffer(
            reinterpret_cast<void *>(static_cast<uintptr_t>(address)),
            static_cast<jlong>(capacity));
}
