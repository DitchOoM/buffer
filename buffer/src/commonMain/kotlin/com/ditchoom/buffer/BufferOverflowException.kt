package com.ditchoom.buffer

/**
 * Thrown when a write operation exceeds the buffer's available space.
 *
 * Thrown consistently across all platforms when:
 * - A relative write (`writeByte`, `writeInt`, etc.) would exceed [WriteBuffer.remaining]
 * - An absolute write (`set`) would exceed the buffer's [WriteBuffer.limit]
 *
 * On JVM/Android the actual is a subclass of `java.nio.BufferOverflowException`,
 * so JVM-only catch sites that catch the native type also catch this one.
 * Native `java.nio.BufferOverflowException` instances raised inside ByteBuffer
 * operations are wrapped in [BaseJvmBuffer] to attach a richer message and
 * rethrown as this subclass.
 */
expect class BufferOverflowException(
    message: String,
) : RuntimeException
