package com.ditchoom.buffer

/**
 * Checks that [bytesNeeded] bytes can be read from the current position.
 * Throws [BufferUnderflowException] if `bytesNeeded` is negative or exceeds
 * [ReadBuffer.remaining]. Used to prevent uncatchable native errors — e.g.
 * Apple's `NSData.subdataWithRange:` throws NSRangeException which crashes
 * the Kotlin/Native process before Kotlin exception handling runs.
 */
internal fun ReadBuffer.checkReadBounds(bytesNeeded: Int) {
    if (bytesNeeded < 0 || remaining() < bytesNeeded) {
        throw BufferUnderflowException(
            "Buffer underflow: cannot read $bytesNeeded byte(s) at position ${position()} " +
                "(limit=${limit()}, remaining=${remaining()})",
        )
    }
}

/**
 * Checks that [bytesNeeded] bytes can be written at the current position.
 * Throws [BufferOverflowException] if `remaining() < bytesNeeded`.
 */
internal fun WriteBuffer.checkWriteBounds(bytesNeeded: Int) {
    if (remaining() < bytesNeeded) {
        throw BufferOverflowException(
            "Buffer overflow: cannot write $bytesNeeded byte(s) at position ${position()} " +
                "(limit=${limit()}, remaining=${remaining()})",
        )
    }
}

/**
 * Checks that [bytesNeeded] bytes can be written at the specified absolute [index].
 * Throws [BufferOverflowException] if `index + bytesNeeded > limit()` or `index < 0`.
 */
internal fun WriteBuffer.checkIndexBounds(
    index: Int,
    bytesNeeded: Int,
) {
    if (index < 0 || index + bytesNeeded > limit()) {
        throw BufferOverflowException(
            "Index out of bounds: cannot write $bytesNeeded byte(s) at index $index " +
                "(limit=${limit()})",
        )
    }
}

/**
 * Validates a proposed limit against the buffer's capacity, matching JVM
 * `ByteBuffer.limit(newLimit)` semantics: `0 <= limit <= capacity`. Throws
 * [IllegalArgumentException] otherwise. Critical on Apple/native buffers, where
 * primitive reads use raw pointer arithmetic and `NSData.subdataWithRange:` raises
 * an uncatchable NSRangeException — a too-large limit would otherwise produce
 * silent OOB reads or crash the K/N process.
 */
internal fun checkLimitBounds(
    limit: Int,
    capacity: Int,
) {
    if (limit < 0 || limit > capacity) {
        throw IllegalArgumentException(
            "limit out of bounds: limit=$limit capacity=$capacity",
        )
    }
}

/**
 * Validates a proposed position against the current limit, matching JVM
 * `ByteBuffer.position(newPosition)` semantics: `0 <= position <= limit`. Throws
 * [IllegalArgumentException] otherwise.
 */
internal fun checkPositionBounds(
    position: Int,
    limit: Int,
) {
    if (position < 0 || position > limit) {
        throw IllegalArgumentException(
            "position out of bounds: position=$position limit=$limit",
        )
    }
}
