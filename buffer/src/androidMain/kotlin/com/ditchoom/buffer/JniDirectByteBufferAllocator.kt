package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Wraps a raw native address as a [ByteBuffer] via JNI's `NewDirectByteBuffer`.
 *
 * This is the supported, hidden-API-free path on Android — the spec function
 * has been part of JNI since 1.4 and is not subject to the non-SDK
 * enforcement that gates `java.nio.DirectByteBuffer(long, int)` on
 * non-debuggable test APKs from API 28+.
 *
 * The companion library is built by `externalNativeBuild` from
 * `src/androidMain/cpp/buffer_jni.cpp`. Consumers who ship the published
 * Android artifact inherit the four common ABIs by default; consumers who
 * strip ABIs must include `arm64-v8a`, `armeabi-v7a`, `x86`, or `x86_64`
 * matching their target.
 *
 * If [System.loadLibrary] fails at class init it throws [UnsatisfiedLinkError].
 * On real Android this is fatal — wrapNativeAddress / deterministic buffers
 * cannot work without it. On the host-JVM unit-test environment
 * (`testDebugUnitTest` / `testReleaseUnitTest`), the .so isn't on
 * `java.library.path` at all; callers of `wrapNativeAddress` must catch the
 * resulting error themselves. `BufferFactoryAndroid.wrapNativeAddress`
 * converts the error to `UnsupportedOperationException` for that reason.
 */
internal object JniDirectByteBufferAllocator {
    init {
        System.loadLibrary("ditchoom_buffer_jni")
    }

    @JvmStatic
    external fun newDirectByteBuffer(
        address: Long,
        capacity: Int,
    ): ByteBuffer
}
