package com.ditchoom.buffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

/**
 * Allocates an owning [LinearBuffer] out of WASM linear memory.
 *
 * Linear memory is not garbage collected, so every buffer this returns must be released with
 * `freeNativeMemory()` / `use { }`; see [LinearBuffer] for the ownership rules.
 */
private fun allocateOwnedLinearBuffer(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    require(size >= 0) { "Buffer size must be non-negative, got $size" }
    val (offset, _) = LinearMemoryAllocator.allocate(size)
    return LinearBuffer(offset, size, byteOrder, owned = true)
}

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateOwnedLinearBuffer(size, byteOrder)

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

/**
 * WASM has no cross-process shared memory, so this falls back to Direct linear memory — same as the
 * JVM, Apple and Linux fallbacks. Bytes are visible to JavaScript in the same process (zero-copy via
 * `wasmExports.memory.buffer`), but not to another worker or process.
 */
internal actual val sharedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateOwnedLinearBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder)
    }

/**
 * Deterministic cleanup on WASM is [LinearBuffer] itself: it implements [CloseableBuffer], and
 * `freeNativeMemory()` returns the block to [LinearMemoryAllocator] for reuse.
 *
 * This is the same buffer type the default factory hands out — WASM has no GC-backed alternative
 * for linear memory, so on this platform the *default* allocation also has to be released. The
 * distinction the other platforms draw (`Arena.ofAuto` vs `Arena.ofShared`, GC vs malloc/free) does
 * not exist here; what `deterministic()` adds is the documented guarantee, which now holds.
 *
 * [threadConfined] is ignored: WASM linear memory is single-threaded.
 */
private val deterministicFactoryInstance: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateOwnedLinearBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder)
    }

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

/**
 * Allocates a buffer with guaranteed native memory access (LinearBuffer).
 * This is equivalent to allocate with Direct zone but makes the intent explicit.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = allocateOwnedLinearBuffer(size, byteOrder)

/**
 * Allocates a buffer with shared memory support.
 * On WASM, falls back to LinearBuffer (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)

actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    require(address >= 0 && address <= Int.MAX_VALUE) {
        "WASM linear memory address must fit in Int range, got $address"
    }
    // Non-owning: the caller owns this address, so freeNativeMemory() must not hand it to the
    // allocator's free list.
    return LinearBuffer(address.toInt(), size, byteOrder, owned = false)
}
