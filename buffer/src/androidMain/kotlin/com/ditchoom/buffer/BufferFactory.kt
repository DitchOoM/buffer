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
            // Try invokeCleaner first (available on host JVM during unit tests, not on ART)
            if (invokeCleanerFn != null) {
                return JvmDeterministicDirectJvmBuffer(
                    ByteBuffer.allocateDirect(size).order(byteOrder.toJava()),
                )
            }
            // Android/ART: use Unsafe.allocateMemory + DirectByteBuffer
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
    val byteBuffer =
        UnsafeMemory.tryWrapAsDirectByteBuffer(address, size)
            ?: throw UnsupportedOperationException(
                "Cannot wrap native address: DirectByteBuffer reflection is not available on this Android version.",
            )
    byteBuffer.order(byteOrder.toJava())
    return DirectJvmBuffer(byteBuffer)
}
