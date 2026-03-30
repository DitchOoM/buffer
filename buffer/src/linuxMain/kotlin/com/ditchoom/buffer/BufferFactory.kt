package com.ditchoom.buffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            return ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

// Linux Default is now GC-managed (ByteArrayBuffer), same as managed()
internal actual val defaultBufferFactory: BufferFactory = managedBufferFactory

internal actual val sharedBufferFactory: BufferFactory = managedBufferFactory

private val deterministicFactoryInstance: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            return NativeBuffer.allocate(size, byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

/**
 * Allocates a buffer with guaranteed native memory access using malloc.
 *
 * Returns a [NativeBuffer] that provides [NativeMemoryAccess.nativeAddress] for
 * zero-copy I/O with io_uring and other native APIs.
 *
 * IMPORTANT: The returned buffer must be explicitly closed to free native memory.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)

/**
 * Allocates a buffer with shared memory support.
 * On Linux, falls back to native allocation (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)

actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer.wrapExternal(address, size, byteOrder)
