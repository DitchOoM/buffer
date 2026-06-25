@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

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

    /**
     * Secondary constructor preserving the originating [cause] (e.g. the native
     * `java.nio.BufferOverflowException` translated by [BaseJvmBuffer]). The
     * native parent has no cause constructor, so we attach via [initCause].
     */
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }

    override val message: String? get() = msg
}
