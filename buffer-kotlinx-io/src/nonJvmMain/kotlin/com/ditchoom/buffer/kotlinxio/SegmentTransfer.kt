package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

internal actual fun ReadBuffer.readIntoSegment(
    dst: ByteArray,
    dstOffset: Int,
    length: Int,
) {
    readInto(dst, dstOffset, length)
}

internal actual fun WriteBuffer.writeSegmentBytes(
    src: ByteArray,
    srcOffset: Int,
    length: Int,
) {
    writeBytes(src, srcOffset, length)
}
