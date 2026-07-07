@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer

actual class BufferUnderflowException actual constructor(
    message: String,
) : RuntimeException(message)
