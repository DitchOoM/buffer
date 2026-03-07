package com.ditchoom.buffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val sharedBufferFactory: BufferFactory = defaultBufferFactory

// Linux NativeBuffer uses malloc/free — already deterministic
internal actual val deterministicBufferFactory: BufferFactory = defaultBufferFactory

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
