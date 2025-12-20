package com.ditchoom.buffer

/**
 * Allocates an unsafe buffer, executes the given block with it, and ensures
 * proper cleanup when the block completes.
 *
 * This is the recommended way to use unsafe buffers as it guarantees proper
 * memory cleanup and provides optimal performance on all platforms:
 * - JVM/Android: Uses sun.misc.Unsafe direct memory
 * - Native: Uses POSIX malloc/free with typed pointer operations
 * - JS: Uses ArrayBuffer with DataView
 * - WASM: Uses native linear memory with Pointer.loadInt()/storeInt() (optimal)
 *
 * @param size The size of the buffer to allocate in bytes
 * @param byteOrder The byte order for multi-byte operations (default: BIG_ENDIAN)
 * @param block The code block to execute with the buffer
 * @return The result of the block
 */
expect inline fun <R> withUnsafeBuffer(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    block: (UnsafeBuffer) -> R,
): R
