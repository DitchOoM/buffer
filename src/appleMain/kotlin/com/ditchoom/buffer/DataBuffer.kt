package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.isEqualToData
import platform.Foundation.subdataWithRange

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
    override fun get(index: Int): Byte = bytePointer[index]

    override fun slice(): ReadBuffer {
        val range = NSMakeRange(position.convert(), (limit - position).convert())
        return DataBuffer(data.subdataWithRange(range), byteOrder = byteOrder)
    }

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        val subDataRange = NSMakeRange(position.convert(), size.convert())

        @Suppress("UNCHECKED_CAST")
        val subDataBytePointer = data.subdataWithRange(subDataRange).bytes as CPointer<ByteVar>
        val result = subDataBytePointer.readBytes(size)
        position += size
        return result
    }

    override fun readString(length: Int, charset: Charset): String {
        if (length == 0) return ""
        val subdata = data.subdataWithRange(NSMakeRange(position.convert(), length.convert()))
        val stringEncoding = charset.toEncoding()

        @Suppress("CAST_NEVER_SUCCEEDS")
        val string = NSString.create(subdata, stringEncoding) as String
        position += length
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
