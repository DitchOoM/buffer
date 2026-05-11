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
 * matching their target. If the .so is unavailable at runtime,
 * [System.loadLibrary] throws [UnsatisfiedLinkError] at class init —
 * intentionally fatal, since the deterministic-buffer factory cannot
 * function without it.
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
