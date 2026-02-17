package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.buf_indexof_int
import com.ditchoom.buffer.cinterop.buf_indexof_int_aligned
import com.ditchoom.buffer.cinterop.buf_indexof_long
import com.ditchoom.buffer.cinterop.buf_indexof_long_aligned
import com.ditchoom.buffer.cinterop.buf_indexof_short
import com.ditchoom.buffer.cinterop.buf_indexof_short_aligned
import com.ditchoom.buffer.cinterop.buf_mismatch
import com.ditchoom.buffer.cinterop.buf_xor_mask
import com.ditchoom.buffer.cinterop.buf_xor_mask_copy
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.isEqualToData
import platform.Foundation.replaceBytesInRange
import platform.Foundation.subdataWithRange
import platform.posix.memchr
import platform.posix.memcmp
import platform.posix.memcpy
import platform.posix.memset

/**
 * Buffer implementation backed by NSMutableData (Apple native memory).
 *
 * This buffer provides direct access to native memory via [nativeAddress] and can be
 * passed to Apple APIs expecting NSData or NSMutableData (since NSMutableData extends NSData).
 *
 * For buffers backed by Kotlin ByteArray (managed memory), use [ByteArrayBuffer] instead.
 *
 * @property data The underlying NSMutableData instance.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
class MutableDataBuffer(
    val data: NSMutableData,
    override val byteOrder: ByteOrder,
) : PlatformBuffer,
    SuspendCloseable,
    Parcelable,
    NativeMemoryAccess {
    private var position: Int = 0
    private var limit: Int = data.length.toInt()
    override val capacity: Int = data.length.toInt()

    @Suppress("UNCHECKED_CAST")
    private val bytePointer: CPointer<ByteVar> = data.mutableBytes as CPointer<ByteVar>

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
        return MutableDataBufferSlice(this, position, limit - position)
    }

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) {
            return ByteArray(0)
        }
        // Direct pointer read to avoid NSData allocation
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

    // endregion

    // region Write operations

    override fun resetForWrite() {
        position = 0
        limit = capacity
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        bytePointer[position++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        bytePointer[index] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) short.reverseBytes() else short
        (bytePointer + position)!!.reinterpret<ShortVar>()[0] = value
        position += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) short.reverseBytes() else short
        (bytePointer + index)!!.reinterpret<ShortVar>()[0] = value
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) int.reverseBytes() else int
        (bytePointer + position)!!.reinterpret<IntVar>()[0] = value
        position += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) int.reverseBytes() else int
        (bytePointer + index)!!.reinterpret<IntVar>()[0] = value
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) long.reverseBytes() else long
        (bytePointer + position)!!.reinterpret<LongVar>()[0] = value
        position += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) long.reverseBytes() else long
        (bytePointer + index)!!.reinterpret<LongVar>()[0] = value
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        if (length < 1) {
            return this
        }
        val range = NSMakeRange(position.convert(), length.convert())
        bytes.usePinned { pin ->
            data.replaceBytesInRange(range, pin.addressOf(offset))
        }
        position += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val bytesToCopy = buffer.remaining()
        val actual = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
        when (actual) {
            is MutableDataBuffer -> {
                // Direct memory copy - no intermediate allocation
                val srcPtr = actual.bytePointer + actual.position()
                val dstPtr = bytePointer + position
                memcpy(dstPtr, srcPtr, bytesToCopy.convert())
            }
            is MutableDataBufferSlice -> {
                // Direct memory copy from slice
                val srcPtr = actual.bytePointer + actual.position()
                val dstPtr = bytePointer + position
                memcpy(dstPtr, srcPtr, bytesToCopy.convert())
            }
            else -> {
                writeBytes(buffer.readByteArray(bytesToCopy))
                return // readByteArray already advances buffer position
            }
        }
        position += bytesToCopy
        buffer.position(buffer.position() + bytesToCopy)
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val string =
            if (text is String) {
                @Suppress("CAST_NEVER_SUCCEEDS")
                text as NSString
            } else {
                @Suppress("CAST_NEVER_SUCCEEDS")
                text.toString() as NSString
            }
        val charsetEncoding = charset.toEncoding()
        val stringData = string.dataUsingEncoding(charsetEncoding)!!

        @Suppress("UNCHECKED_CAST")
        val stringBytes = stringData.bytes as CPointer<ByteVar>
        val stringLength = stringData.length.toInt()

        memcpy(bytePointer + position, stringBytes, stringLength.convert())
        position += stringLength
        return this
    }

    // endregion

    // region Position/Limit management

    override fun limit(): Int = limit

    override fun position(): Int = position

    override fun position(newPosition: Int) {
        position = newPosition
    }

    override suspend fun close() = Unit

    // endregion

    // region Optimized bulk operations

    /**
     * SIMD-optimized XOR mask using buf_xor_mask (auto-vectorized by clang at -O2).
     */
    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        if (mask == 0) return
        val size = limit - position
        if (size == 0) return
        // The mask Int is big-endian (byte 0 = MSB). Native memory is little-endian,
        // so reverseBytes() ensures mask_bytes[0] from memcpy matches the first byte to XOR.
        val nativeMask = mask.reverseBytes().toUInt()
        buf_xor_mask(
            (bytePointer + position)!!.reinterpret(),
            size.convert(),
            nativeMask,
            maskOffset.convert(),
        )
    }

    /**
     * SIMD-optimized fused copy + XOR mask using buf_xor_mask_copy (auto-vectorized by clang at -O2).
     */
    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        val size = source.remaining()
        if (size == 0) return
        if (mask == 0) {
            write(source)
            return
        }

        val nativeMask = mask.reverseBytes().toUInt()
        val actual = (source as? PlatformBuffer)?.unwrap() ?: source
        when (actual) {
            is MutableDataBuffer -> {
                buf_xor_mask_copy(
                    (actual.bytePointer + actual.position())!!.reinterpret(),
                    (bytePointer + position)!!.reinterpret(),
                    size.convert(),
                    nativeMask,
                    maskOffset.convert(),
                )
            }
            is MutableDataBufferSlice -> {
                buf_xor_mask_copy(
                    (actual.bytePointer + actual.position())!!.reinterpret(),
                    (bytePointer + position)!!.reinterpret(),
                    size.convert(),
                    nativeMask,
                    maskOffset.convert(),
                )
            }
            is ByteArrayBuffer -> {
                actual.backingArray.usePinned { pinned ->
                    buf_xor_mask_copy(
                        pinned.addressOf(actual.position()).reinterpret(),
                        (bytePointer + position)!!.reinterpret(),
                        size.convert(),
                        nativeMask,
                        maskOffset.convert(),
                    )
                }
            }
            else -> {
                super.xorMaskCopy(source, mask, maskOffset)
                return
            }
        }
        position += size
        source.position(source.position() + size)
    }

    /**
     * Optimized fill using memset (OS-provided SIMD implementation).
     */
    override fun fill(value: Byte): WriteBuffer {
        val count = remaining()
        if (count == 0) return this
        memset(bytePointer + position, value.toInt(), count.convert())
        position += count
        return this
    }

    // endregion

    // region Optimized comparison operations

    /**
     * Optimized content comparison using memcmp.
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        val actual = (other as? PlatformBuffer)?.unwrap() ?: other
        return when (actual) {
            is MutableDataBuffer -> {
                memcmp(bytePointer + position, actual.bytePointer + actual.position(), size.convert()) == 0
            }
            is MutableDataBufferSlice -> {
                memcmp(bytePointer + position, actual.bytePointer + actual.position(), size.convert()) == 0
            }
            is ByteArrayBuffer -> {
                actual.backingArray.usePinned { pinned ->
                    memcmp(bytePointer + position, pinned.addressOf(actual.position()), size.convert()) == 0
                }
            }
            else -> {
                // Fallback for other buffer types
                for (i in 0 until size) {
                    if (get(position + i) != other.get(other.position() + i)) {
                        return false
                    }
                }
                true
            }
        }
    }

    /**
     * SIMD-optimized mismatch using buf_mismatch (auto-vectorized by clang at -O2).
     */
    override fun mismatch(other: ReadBuffer): Int {
        val thisRemaining = remaining()
        val otherRemaining = other.remaining()
        val minLength = minOf(thisRemaining, otherRemaining)

        if (minLength == 0) {
            return if (thisRemaining != otherRemaining) 0 else -1
        }

        val actual = (other as? PlatformBuffer)?.unwrap() ?: other
        val result =
            when (actual) {
                is MutableDataBuffer -> {
                    buf_mismatch(
                        (bytePointer + position)!!.reinterpret(),
                        (actual.bytePointer + actual.position())!!.reinterpret(),
                        minLength.convert(),
                    ).toInt()
                }
                is MutableDataBufferSlice -> {
                    buf_mismatch(
                        (bytePointer + position)!!.reinterpret(),
                        (actual.bytePointer + actual.position())!!.reinterpret(),
                        minLength.convert(),
                    ).toInt()
                }
                is ByteArrayBuffer -> {
                    actual.backingArray.usePinned { pinned ->
                        buf_mismatch(
                            (bytePointer + position)!!.reinterpret(),
                            pinned.addressOf(actual.position()).reinterpret(),
                            minLength.convert(),
                        ).toInt()
                    }
                }
                else -> {
                    // Fallback for other buffer types
                    for (i in 0 until minLength) {
                        if (get(position + i) != other.get(other.position() + i)) {
                            return i
                        }
                    }
                    -1
                }
            }

        if (result != -1) return result
        return if (thisRemaining != otherRemaining) minLength else -1
    }

    /**
     * Optimized single byte indexOf using memchr.
     */
    override fun indexOf(byte: Byte): Int {
        val size = remaining()
        if (size == 0) return -1

        val result = memchr(bytePointer + position, byte.toInt(), size.convert())
        return if (result == null) {
            -1
        } else {
            @Suppress("UNCHECKED_CAST")
            ((result as CPointer<ByteVar>).toLong() - (bytePointer + position).toLong()).toInt()
        }
    }

    /**
     * Optimized indexOf(Short) using native C implementation.
     * When [aligned] is true, uses SIMD auto-vectorized aligned scanning.
     */
    override fun indexOf(
        value: Short,
        aligned: Boolean,
    ): Int {
        val size = remaining()
        if (size < 2) return -1
        val nativeValue = if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
        val fn = if (aligned) ::buf_indexof_short_aligned else ::buf_indexof_short
        return fn(
            (bytePointer + position)!!.reinterpret(),
            size.convert(),
            nativeValue.toUShort(),
        ).toInt()
    }

    /**
     * Optimized indexOf(Int) using native C implementation.
     * When [aligned] is true, uses SIMD auto-vectorized aligned scanning.
     */
    override fun indexOf(
        value: Int,
        aligned: Boolean,
    ): Int {
        val size = remaining()
        if (size < 4) return -1
        val nativeValue = if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
        val fn = if (aligned) ::buf_indexof_int_aligned else ::buf_indexof_int
        return fn(
            (bytePointer + position)!!.reinterpret(),
            size.convert(),
            nativeValue.toUInt(),
        ).toInt()
    }

    /**
     * Optimized indexOf(Long) using native C implementation.
     * When [aligned] is true, uses SIMD auto-vectorized aligned scanning.
     */
    override fun indexOf(
        value: Long,
        aligned: Boolean,
    ): Int {
        val size = remaining()
        if (size < 8) return -1
        val nativeValue = if (byteOrder == ByteOrder.BIG_ENDIAN) value.reverseBytes() else value
        val fn = if (aligned) ::buf_indexof_long_aligned else ::buf_indexof_long
        return fn(
            (bytePointer + position)!!.reinterpret(),
            size.convert(),
            nativeValue.toULong(),
        ).toInt()
    }

    // endregion

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutableDataBuffer) return false
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
        return result
    }
}

/**
 * Zero-copy slice view of a MutableDataBuffer.
 * Uses pointer arithmetic instead of subdataWithRange to avoid NSData allocation.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
class MutableDataBufferSlice(
    private val parent: MutableDataBuffer,
    private val sliceOffset: Int,
    private val sliceLength: Int,
) : ReadBuffer,
    NativeMemoryAccess {
    private var position: Int = 0
    private var limit: Int = sliceLength

    // Pointer to the start of this slice's data
    @Suppress("UNCHECKED_CAST")
    val bytePointer: CPointer<ByteVar> = (parent.data.mutableBytes as CPointer<ByteVar> + sliceOffset)!!

    override val nativeAddress: Long get() = bytePointer.toLong()

    override val nativeSize: Long get() = sliceLength.toLong()

    override val byteOrder: ByteOrder get() = parent.byteOrder

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

    override fun slice(): ReadBuffer = MutableDataBufferSlice(parent, sliceOffset + position, limit - position)

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

    /**
     * Optimized single byte indexOf using memchr.
     */
    override fun indexOf(byte: Byte): Int {
        val size = remaining()
        if (size == 0) return -1

        val result = memchr(bytePointer + position, byte.toInt(), size.convert())
        return if (result == null) {
            -1
        } else {
            @Suppress("UNCHECKED_CAST")
            ((result as CPointer<ByteVar>).toLong() - (bytePointer + position).toLong()).toInt()
        }
    }
}
