package com.ditchoom.buffer

/**
 * JVM/Android actual: subclass of [java.nio.BufferOverflowException] so that
 * JVM-only catch sites that match the native nio type also catch the common
 * type. The native parent has no message constructor, so we keep the message
 * via property override.
 */
actual class BufferOverflowException actual constructor(
    message: String,
) : java.nio.BufferOverflowException() {
    private val msg: String = message

    override val message: String? get() = msg
}
