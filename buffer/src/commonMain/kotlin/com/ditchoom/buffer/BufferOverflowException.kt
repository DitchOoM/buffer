package com.ditchoom.buffer

/**
 * Thrown when a write operation exceeds the buffer's available space.
 *
 * This exception is thrown consistently across all platforms when:
 * - A relative write (`writeByte`, `writeInt`, etc.) would exceed [WriteBuffer.remaining]
 * - An absolute write (`set`) would exceed the buffer's [WriteBuffer.limit]
 *
 * On JVM, the native [java.nio.BufferOverflowException] is caught internally and
 * rethrown as this exception, so callers only need to catch one type in common code.
 */
class BufferOverflowException(
    message: String,
) : RuntimeException(message)
