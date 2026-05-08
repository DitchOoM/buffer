package com.ditchoom.buffer

/**
 * Thrown when a read operation exceeds the buffer's available bytes.
 *
 * Thrown consistently across all platforms when a relative read (`readByte`,
 * `readInt`, `readString`, etc.) would require more bytes than
 * [ReadBuffer.remaining], or when the caller passes a negative length.
 *
 * On JVM/Android the actual is a subclass of `java.nio.BufferUnderflowException`,
 * so JVM-only catch sites that catch the native type also catch this one.
 * Native `java.nio.BufferUnderflowException` instances raised inside ByteBuffer
 * operations are wrapped in [BaseJvmBuffer] to attach a richer message and
 * rethrown as this subclass.
 */
expect class BufferUnderflowException(
    message: String,
) : RuntimeException
