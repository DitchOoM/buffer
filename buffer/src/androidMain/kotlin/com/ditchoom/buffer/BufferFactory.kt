@file:JvmName("BufferFactoryAndroid")

package com.ditchoom.buffer

import android.annotation.SuppressLint
import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.allocate(size).order(byteOrder.toJava()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

private val deterministicFactoryInstance: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            // Tier 1 — `Unsafe.invokeCleaner` (host JVM 9+). The Android
            // source set is loaded both in production (real Android, ART
            // runtime) and in `testDebugUnitTest` / `testReleaseUnitTest`
            // (the Android Gradle Plugin's host-JVM-with-`android.jar`-stubs
            // unit-test environment that downstream library consumers use
            // for fast-feedback testing). On real ART, `invokeCleanerFn`
            // resolves to null because ART doesn't expose
            // `Unsafe.invokeCleaner` (per the comment in
            // `InvokeCleanerHelper.kt`), so device behavior falls through
            // to Tier 2 unchanged. On host JDK 9+ (notably JDK 21 where
            // the `(long, int)` `DirectByteBuffer` ctor used by Tier 2's
            // reflection no longer exists), Tier 1 succeeds and dodges
            // the broken reflection. Mirrors the JVM target's tiered
            // factory; without it, downstream consumers running their
            // Android source set against a modern host JDK hit
            // `UnsupportedOperationException` from Tier 2's
            // reflection lookup.
            if (invokeCleanerFn != null) {
                return AndroidDeterministicDirectJvmBuffer(
                    ByteBuffer.allocateDirect(size).order(byteOrder.toJava()),
                )
            }
            // Tier 2 — Unsafe.allocateMemory + DirectByteBuffer ctor
            // reflection. The path real Android devices take.
            return AndroidDeterministicUnsafeJvmBuffer.allocate(size, byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

@SuppressLint("NewApi")
internal actual val sharedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && size > 0) {
                try {
                    val sharedMemory = SharedMemory.create(null, size)
                    return ParcelableSharedMemoryBuffer(
                        sharedMemory.mapReadWrite().order(byteOrder.toJava()),
                        sharedMemory,
                    )
                } catch (_: Exception) {
                    // Fall back to direct allocation if SharedMemory fails
                }
            }
            return DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

/**
 * Allocates a buffer with guaranteed native memory access (DirectJvmBuffer).
 * Uses a direct ByteBuffer with accessible native memory address.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))

/**
 * Allocates a buffer with shared memory (SharedMemory) if available.
 * Falls back to DirectJvmBuffer if SharedMemory is not supported (API < 27) or size is 0.
 *
 * SharedMemory enables zero-copy IPC via Parcelable on Android.
 */
@SuppressLint("NewApi")
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = BufferFactory.shared().allocate(size, byteOrder)

actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    // JNI NewDirectByteBuffer is the supported Android path; reflective
    // DirectByteBuffer(long, int) is hidden-API-gated on non-debuggable
    // test APKs from API 28+.
    val byteBuffer = JniDirectByteBufferAllocator.newDirectByteBuffer(address, size)
    byteBuffer.order(byteOrder.toJava())
    return DirectJvmBuffer(byteBuffer)
}
