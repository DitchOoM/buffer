package com.ditchoom.buffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            val (offset, _) = LinearMemoryAllocator.allocate(size)
            return LinearBuffer(offset, size, byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder)
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(ByteArray(size), byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder)
    }

internal actual val sharedBufferFactory: BufferFactory = defaultBufferFactory

// WASM LinearBuffer uses linear memory — already deterministic
private val deterministicFactoryInstance: BufferFactory = defaultBufferFactory

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

/**
 * Allocates a buffer with guaranteed native memory access (LinearBuffer).
 * This is equivalent to allocate with Direct zone but makes the intent explicit.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val (offset, _) = LinearMemoryAllocator.allocate(size)
    return LinearBuffer(offset, size, byteOrder)
}

/**
 * Allocates a buffer with shared memory support.
 * On WASM, falls back to LinearBuffer (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)
