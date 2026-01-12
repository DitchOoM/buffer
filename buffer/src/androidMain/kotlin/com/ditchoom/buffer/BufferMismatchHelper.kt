package com.ditchoom.buffer

import java.nio.ByteBuffer

/** Uses default ReadBuffer.mismatch() implementation on Android. */
internal actual object BufferMismatchHelper {
    actual fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
    ): Int = USE_DEFAULT_MISMATCH
}
