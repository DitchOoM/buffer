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
import kotlinx.cinterop.toLong
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.subdataWithRange
import platform.posix.memchr
import platform.posix.memcmp

/**
 * Read-only buffer backed by NSData (Apple native memory).
 *
 * This buffer provides zero-copy read access to NSData. It does not copy the
 * underlying data, making it efficient for reading large data from Apple APIs.
 *
 * For mutable buffers, use [MutableDataBuffer] instead.
 *
 * @property data The underlying NSData instance (read-only).
 */
@Suppress("UNCHECKED_CAST")
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, UnsafeNumber::class)
class NSDataBuffer(
    val data: NSData,
    override val byteOrder: ByteOrder,
) : ReadBuffer,
    SuspendCloseable,
    NativeMemoryAccess {
    private var position: Int = 0
    private var limit: Int = data.length.toInt()
    val capacity: Int = data.length.toInt()

    @Suppress("UNCHECKED_CAST")
    private val bytePointer: CPointer<ByteVar> = data.bytes as CPointer<ByteVar>

    /**
     * The native memory address for C interop.
     * Can be used with Kotlin/Native's CPointer APIs or passed to native code.
     */
    override val nativeAddress: Long get() = bytePointer.toLong()

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    // region Read operations

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte(): Byte = bytePointer[position++]

    override fun get(index: Int): Byte = bytePointer[index]

    override fun getShort(index: Int): Short {
        val ptr = (bytePointer + index)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun getInt(index: Int): Int {
        val ptr = (bytePointer + index)!!.reinterpret<IntVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun getLong(index: Int): Long {
        val ptr = (bytePointer + index)!!.reinterpret<LongVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readShort(): Short {
        val ptr = (bytePointer + position)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        position += 2
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readInt(): Int {
        val ptr = (bytePointer + position)!!.reinterpret<IntVar>()
        val value = ptr[0]
        position += 4
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readLong(): Long {
        val ptr = (bytePointer + position)!!.reinterpret<LongVar>()
        val value = ptr[0]
        position += 8
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun slice(): ReadBuffer {
        // Zero-copy slice using pointer arithmetic
        return NSDataBufferSlice(this, position, limit - position)
    }

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        val result = (bytePointer + position)!!.readBytes(size)
        position += size
        return result
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        val subdata =
            data.subdataWithRange(
                NSMakeRange(position.convert(), length.convert()),
            )
        val stringEncoding = charset.toEncoding()
        val string =
            NSString.create(subdata, stringEncoding)?.toString()
                ?: throw IllegalArgumentException("Failed to decode bytes with charset: $charset")
        position += length
        return string
    }

    // endregion

    // region Position/Limit management

    override fun remaining(): Int = limit - position

    override fun hasRemaining(): Boolean = position < limit

    override fun position(): Int = position

    override fun position(newPosition: Int) {
        position = newPosition
    }

    override fun limit(): Int = limit

    // endregion

    // region Optimized comparison using memcmp

    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val actual = (other as? PlatformBuffer)?.unwrap() ?: other
        if (actual is NSDataBuffer) {
            return memcmp(
                bytePointer + position,
                actual.bytePointer + actual.position,
                remaining().convert(),
            ) == 0
        }
        if (actual is MutableDataBuffer) {
            val otherPointer = actual.data.mutableBytes as CPointer<ByteVar>
            return memcmp(
                bytePointer + position,
                otherPointer + actual.position(),
                remaining().convert(),
            ) == 0
        }
        return super.contentEquals(other)
    }

    // endregion

    // region Optimized indexOf using memchr

    override fun indexOf(byte: Byte): Int {
        val searchPtr = bytePointer + position
        val result = memchr(searchPtr, byte.toInt(), remaining().convert())
        return if (result != null) {
            (result.toLong() - searchPtr!!.toLong()).toInt()
        } else {
            -1
        }
    }

    // endregion

    override suspend fun close() {
        // NSData is reference-counted by ARC, no explicit cleanup needed
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NSDataBuffer) return false
        if (position != other.position) return false
        if (limit != other.limit) return false
        if (capacity != other.capacity) return false
        return data == other.data
    }

    override fun hashCode(): Int {
        var result = position
        result = 31 * result + limit
        result = 31 * result + capacity
        return result
    }

    override fun toString() = "NSDataBuffer[pos=$position lim=$limit cap=$capacity]"
}

/**
 * Zero-copy slice of an NSDataBuffer.
 * Uses pointer arithmetic to reference a portion of the parent buffer's memory.
 */
@Suppress("UNCHECKED_CAST")
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, UnsafeNumber::class)
internal class NSDataBufferSlice(
    private val parent: NSDataBuffer,
    private val sliceOffset: Int,
    val capacity: Int,
) : ReadBuffer,
    SuspendCloseable,
    NativeMemoryAccess {
    private var position: Int = 0
    private var limit: Int = capacity

    // Pointer to the start of this slice's data
    val bytePointer: CPointer<ByteVar> = (parent.data.bytes as CPointer<ByteVar> + sliceOffset)!!

    override val byteOrder: ByteOrder get() = parent.byteOrder

    override val nativeAddress: Long get() = bytePointer.toLong()

    override val nativeSize: Long get() = capacity.toLong()

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte(): Byte = bytePointer[position++]

    override fun get(index: Int): Byte = bytePointer[index]

    override fun getShort(index: Int): Short {
        val ptr = (bytePointer + index)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun getInt(index: Int): Int {
        val ptr = (bytePointer + index)!!.reinterpret<IntVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun getLong(index: Int): Long {
        val ptr = (bytePointer + index)!!.reinterpret<LongVar>()
        val value = ptr[0]
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readShort(): Short {
        val ptr = (bytePointer + position)!!.reinterpret<ShortVar>()
        val value = ptr[0]
        position += 2
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readInt(): Int {
        val ptr = (bytePointer + position)!!.reinterpret<IntVar>()
        val value = ptr[0]
        position += 4
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun readLong(): Long {
        val ptr = (bytePointer + position)!!.reinterpret<LongVar>()
        val value = ptr[0]
        position += 8
        return if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
    }

    override fun slice(): ReadBuffer = NSDataBufferSlice(parent, sliceOffset + position, limit - position)

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        val result = (bytePointer + position)!!.readBytes(size)
        position += size
        return result
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        val subdata =
            parent.data.subdataWithRange(
                NSMakeRange((sliceOffset + position).convert(), length.convert()),
            )
        val stringEncoding = charset.toEncoding()
        val string =
            NSString.create(subdata, stringEncoding)?.toString()
                ?: throw IllegalArgumentException("Failed to decode bytes with charset: $charset")
        position += length
        return string
    }

    override fun remaining(): Int = limit - position

    override fun hasRemaining(): Boolean = position < limit

    override fun position(): Int = position

    override fun position(newPosition: Int) {
        position = newPosition
    }

    override fun limit(): Int = limit

    override fun indexOf(byte: Byte): Int {
        val searchPtr = bytePointer + position
        val result = memchr(searchPtr, byte.toInt(), remaining().convert())
        return if (result != null) {
            (result.toLong() - searchPtr!!.toLong()).toInt()
        } else {
            -1
        }
    }

    override suspend fun close() {
        // No cleanup needed
    }

    override fun toString() = "NSDataBufferSlice[pos=$position lim=$limit cap=$capacity offset=$sliceOffset]"
}
