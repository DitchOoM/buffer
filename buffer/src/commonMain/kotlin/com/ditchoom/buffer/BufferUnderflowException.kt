package com.ditchoom.buffer

/**
 * Thrown when a read operation exceeds the buffer's available bytes.
 *
 * This exception is thrown consistently across all platforms when a relative
 * read (`readByte`, `readInt`, `readString`, etc.) would require more bytes
 * than [ReadBuffer.remaining], or when the caller passes a negative length.
 *
 * On JVM, the native [java.nio.BufferUnderflowException] is caught internally
 * and rethrown as this exception, so callers only need to catch one type in
 * common code.
 */
class BufferUnderflowException(
    message: String,
) : RuntimeException(message)
