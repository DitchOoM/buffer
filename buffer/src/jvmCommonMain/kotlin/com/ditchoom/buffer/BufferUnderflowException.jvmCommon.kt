package com.ditchoom.buffer

/**
 * JVM/Android actual: subclass of [java.nio.BufferUnderflowException] so that
 * JVM-only catch sites that match the native nio type also catch the common
 * type. The native parent has no message constructor, so we keep the message
 * via property override.
 */
actual class BufferUnderflowException actual constructor(
    message: String,
) : java.nio.BufferUnderflowException() {
    private val msg: String = message

    override val message: String? get() = msg
}
