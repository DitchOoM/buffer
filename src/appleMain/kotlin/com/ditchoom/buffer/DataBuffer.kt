package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.isEqualToData
import platform.Foundation.subdataWithRange

@Suppress("OPT_IN_USAGE")
open class DataBuffer(
    val data: NSData,
    override val byteOrder: ByteOrder
) : ReadBuffer, SuspendCloseable, Parcelable {

    protected var position: Int = 0
    protected var limit: Int = data.length.toInt()
    open val capacity: Int = data.length.toInt()

    @Suppress("UNCHECKED_CAST")
    private val bytePointer = this.data.bytes as CPointer<ByteVar>

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte() = bytePointer[position++]

    override fun slice(): ReadBuffer {
        val range = NSMakeRange(position.convert(), (limit - position).convert())
        return DataBuffer(data.subdataWithRange(range), byteOrder = byteOrder)
    }

    override fun readByteArray(size: Int): ByteArray {
        val result = bytePointer.readBytes(size)
        position += size
        return result
    }

    override fun readShort(): Short {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    (0xff and bytePointer[position + 0].toInt() shl 8)
                        or (0xff and bytePointer[position + 1].toInt() shl 0)
                    ).toShort()
            else
                (
                    (0xff and bytePointer[position + 1].toInt() shl 8)
                        or (0xff and bytePointer[position + 0].toInt() shl 0)
                    ).toShort()
        position += Short.SIZE_BYTES
        return value
    }

    override fun readInt(): Int {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    0xff and bytePointer[position + 0].toInt() shl 24
                        or (0xff and bytePointer[position + 1].toInt() shl 16)
                        or (0xff and bytePointer[position + 2].toInt() shl 8)
                        or (0xff and bytePointer[position + 3].toInt() shl 0)
                    )
            else
                (
                    0xff and bytePointer[position + 3].toInt() shl 24
                        or (0xff and bytePointer[position + 2].toInt() shl 16)
                        or (0xff and bytePointer[position + 1].toInt() shl 8)
                        or (0xff and bytePointer[position + 0].toInt() shl 0)
                    )
        position += Int.SIZE_BYTES
        return value
    }

    override fun readLong(): Long {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    bytePointer[position + 0].toLong() shl 56
                        or (bytePointer[position + 1].toLong() and 0xff shl 48)
                        or (bytePointer[position + 2].toLong() and 0xff shl 40)
                        or (bytePointer[position + 3].toLong() and 0xff shl 32)
                        or (bytePointer[position + 4].toLong() and 0xff shl 24)
                        or (bytePointer[position + 5].toLong() and 0xff shl 16)
                        or (bytePointer[position + 6].toLong() and 0xff shl 8)
                        or (bytePointer[position + 7].toLong() and 0xff)
                    )
            else
                (
                    bytePointer[position + 7].toLong() shl 56
                        or (bytePointer[position + 6].toLong() and 0xff shl 48)
                        or (bytePointer[position + 5].toLong() and 0xff shl 40)
                        or (bytePointer[position + 4].toLong() and 0xff shl 32)
                        or (bytePointer[position + 3].toLong() and 0xff shl 24)
                        or (bytePointer[position + 2].toLong() and 0xff shl 16)
                        or (bytePointer[position + 1].toLong() and 0xff shl 8)
                        or (bytePointer[position + 0].toLong() and 0xff)
                    )
        position += Long.SIZE_BYTES
        return value
    }

    override fun readUtf8(bytes: Int): CharSequence {
        if (bytes == 0) return ""
        val subdata = data.subdataWithRange(NSMakeRange(position.convert(), bytes.convert()))

        @Suppress("CAST_NEVER_SUCCEEDS")
        val string = NSString.create(subdata, NSUTF8StringEncoding) as String
        position += bytes
        return string
    }

    override fun limit() = limit
    override fun position() = position
    override fun position(newPosition: Int) {
        position = newPosition
    }

    override suspend fun close() = Unit

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataBuffer) return false
        if (position != other.position) return false
        if (limit != other.limit) return false
        if (capacity != other.capacity) return false
        if (!data.isEqualToData(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + limit.hashCode()
        result = 31 * result + capacity.hashCode()
        result = 31 * result + byteOrder.hashCode()
        result = 31 * result + bytePointer.hashCode()
        return result
    }
}
