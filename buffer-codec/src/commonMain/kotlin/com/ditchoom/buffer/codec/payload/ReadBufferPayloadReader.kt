package com.ditchoom.buffer.codec.payload

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool

class ReadBufferPayloadReader(
    private val buffer: ReadBuffer,
) : PayloadReader {
    private var released = false

    override fun readByte(): Byte {
        checkNotReleased()
        return buffer.readByte()
    }

    override fun readShort(): Short {
        checkNotReleased()
        return buffer.readShort()
    }

    override fun readInt(): Int {
        checkNotReleased()
        return buffer.readInt()
    }

    override fun readLong(): Long {
        checkNotReleased()
        return buffer.readLong()
    }

    override fun readFloat(): Float {
        checkNotReleased()
        return buffer.readFloat()
    }

    override fun readDouble(): Double {
        checkNotReleased()
        return buffer.readDouble()
    }

    override fun readString(length: Int): String {
        checkNotReleased()
        return buffer.readString(length, Charset.UTF8)
    }

    override fun remaining(): Int {
        checkNotReleased()
        return buffer.remaining()
    }

    override fun copyToBuffer(zone: AllocationZone): ReadBuffer {
        checkNotReleased()
        val size = buffer.remaining()
        val copy = PlatformBuffer.allocate(size, zone)
        copy.write(buffer.slice())
        copy.resetForRead()
        return copy
    }

    override fun copyToBuffer(pool: BufferPool): ReadBuffer {
        checkNotReleased()
        val size = buffer.remaining()
        val pooled = pool.acquire(size)
        pooled.write(buffer.slice())
        pooled.resetForRead()
        return pooled
    }

    override fun transferTo(buffer: WriteBuffer) {
        checkNotReleased()
        buffer.write(this.buffer)
    }

    fun release() {
        released = true
    }

    private fun checkNotReleased() {
        check(!released) { "PayloadReader has been released" }
    }
}
