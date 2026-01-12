@file:Suppress("unused") // Loaded at runtime via multi-release JAR, not referenced directly

package com.ditchoom.buffer

import java.nio.ByteBuffer

internal object BufferMismatchHelper {
    fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
    ): Int = buffer1.mismatch(buffer2)
}
