package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

interface Codec<T> {
    fun decode(buffer: ReadBuffer): T

    fun encode(
        buffer: WriteBuffer,
        value: T,
    )

    fun sizeOf(value: T): Int? = null
}
