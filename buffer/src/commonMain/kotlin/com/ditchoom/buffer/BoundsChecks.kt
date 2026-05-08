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
