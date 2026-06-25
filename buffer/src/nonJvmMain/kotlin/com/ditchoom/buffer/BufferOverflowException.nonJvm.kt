@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer

actual class BufferOverflowException actual constructor(
    message: String,
) : RuntimeException(message)
