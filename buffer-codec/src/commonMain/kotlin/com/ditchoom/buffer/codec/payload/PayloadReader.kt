package com.ditchoom.buffer.codec.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool

interface PayloadReader {
    fun readByte(): Byte

    fun readShort(): Short

    fun readInt(): Int

    fun readLong(): Long

    fun readFloat(): Float

    fun readDouble(): Double

    fun readString(length: Int): String

    fun remaining(): Int

    fun copyToBuffer(factory: BufferFactory = BufferFactory.Default): ReadBuffer

    fun copyToBuffer(pool: BufferPool): ReadBuffer

    fun transferTo(buffer: WriteBuffer)

    fun release()
}
