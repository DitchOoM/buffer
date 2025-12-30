package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.isEqualToData
import platform.Foundation.subdataWithRange

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
open class DataBuffer(
    val data: NSData,
    override val byteOrder: ByteOrder,
) : ReadBuffer,
    SuspendCloseable,
    Parcelable {
    protected var position: Int = 0
    protected var limit: Int = data.length.toInt()
    open val capacity: Int = data.length.toInt()

    @Suppress("UNCHECKED_CAST")
    internal val bytePointer = this.data.bytes as CPointer<ByteVar>

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte() = bytePointer[position++]

    override fun get(index: Int): Byte = bytePointer[index]

    override fun readShort(): Short {
        val ptr = (bytePointer + position)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        position += 2
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun readInt(): Int {
        val ptr = (bytePointer + position)!!.reinterpret<IntVar>()
        val value = ptr[0]
        position += 4
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun readLong(): Long {
        val ptr = (bytePointer + position)!!.reinterpret<LongVar>()
        val value = ptr[0]
        position += 8
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun slice(): ReadBuffer {
        // Zero-copy slice using pointer arithmetic instead of subdataWithRange
        // This avoids creating new NSData objects which cause memory pressure at high frequency
        return DataBufferSlice(this, position, limit - position)
    }

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        // Use direct pointer read instead of subdataWithRange to avoid NSData allocation
        val result = (bytePointer + position)!!.readBytes(size)
        position += size
        return result
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        val subdata = data.subdataWithRange(NSMakeRange(position.convert(), length.convert()))
        val stringEncoding = charset.toEncoding()

        @Suppress("CAST_NEVER_SUCCEEDS")
        @OptIn(kotlinx.cinterop.BetaInteropApi::class)
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

/**
 * Zero-copy slice view of a DataBuffer.
 * Uses pointer arithmetic instead of subdataWithRange to avoid NSData allocation.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
internal class DataBufferSlice(
    private val parent: DataBuffer,
    private val sliceOffset: Int,
    private val sliceLength: Int,
) : ReadBuffer {
    private var position: Int = 0
    private var limit: Int = sliceLength

    // Pointer to the start of this slice's data (non-null since parent.bytePointer is valid)
    private val slicePointer: CPointer<ByteVar> = (parent.bytePointer + sliceOffset)!!

    override val byteOrder: ByteOrder get() = parent.byteOrder

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte(): Byte = slicePointer[position++]

    override fun get(index: Int): Byte = slicePointer[index]

    override fun readShort(): Short {
        val ptr = (slicePointer + position)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        position += 2
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun readInt(): Int {
        val ptr = (slicePointer + position)!!.reinterpret<IntVar>()
        val value = ptr[0]
        position += 4
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun readLong(): Long {
        val ptr = (slicePointer + position)!!.reinterpret<LongVar>()
        val value = ptr[0]
        position += 8
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.toBigEndian() else value
    }

    override fun slice(): ReadBuffer = DataBufferSlice(parent, sliceOffset + position, limit - position)

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        val result = (slicePointer + position)!!.readBytes(size)
        position += size
        return result
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        // For string conversion, we need to use NSData/NSString
        val subdata =
            parent.data.subdataWithRange(
                NSMakeRange((sliceOffset + position).convert(), length.convert()),
            )
        val stringEncoding = charset.toEncoding()

        @Suppress("CAST_NEVER_SUCCEEDS")
        @OptIn(kotlinx.cinterop.BetaInteropApi::class)
        val string = NSString.create(subdata, stringEncoding) as String
        position += length
        return string
    }

    override fun limit(): Int = limit

    override fun position(): Int = position

    override fun position(newPosition: Int) {
        position = newPosition
    }
}

// Byte swap utilities for endianness conversion (ARM64 is little endian)
private fun Short.toBigEndian(): Short = (((this.toInt() and 0xFF) shl 8) or ((this.toInt() shr 8) and 0xFF)).toShort()

private fun Int.toBigEndian(): Int =
    ((this and 0xFF) shl 24) or
        ((this and 0xFF00) shl 8) or
        ((this shr 8) and 0xFF00) or
        ((this shr 24) and 0xFF)

private fun Long.toBigEndian(): Long =
    ((this and 0xFFL) shl 56) or
        ((this and 0xFF00L) shl 40) or
        ((this and 0xFF0000L) shl 24) or
        ((this and 0xFF000000L) shl 8) or
        ((this shr 8) and 0xFF000000L) or
        ((this shr 24) and 0xFF0000L) or
        ((this shr 40) and 0xFF00L) or
        ((this shr 56) and 0xFFL)
