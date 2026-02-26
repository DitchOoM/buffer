@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

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
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_convert_utf16le_to_utf8
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_convert_utf8_to_chararray
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_utf16_length_from_utf8
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_validate_utf8
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.posix.free
import platform.posix.malloc
import platform.posix.memchr
import platform.posix.memcmp
import platform.posix.memset

/**
 * Buffer implementation using native memory (malloc/free) on Linux.
 *
 * Provides true zero-copy access for io_uring and other native I/O operations.
 * Memory is allocated outside Kotlin/Native GC heap - no pinning required.
 *
 * IMPORTANT: Must be explicitly closed to free native memory.
 *
 * Usage with io_uring:
 * ```kotlin
 * val buffer = NativeBuffer.allocate(65536)
 * val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!
 * io_uring_prep_recv(sqe, sockfd, ptr, buffer.capacity, 0)
 * // ... after read completes ...
 * buffer.position(bytesRead)
 * buffer.resetForRead()
 * return buffer // caller is responsible for closing
 * ```
 */
class NativeBuffer private constructor(
    private val ptr: CPointer<ByteVar>,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : PlatformBuffer,
    NativeMemoryAccess {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private var closed: Boolean = false

    override val nativeAddress: Long get() = ptr.toLong()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    // All current Kotlin/Native targets (ARM64, x86_64) are little-endian.
    // This would need updating if a big-endian target (e.g. s390x) is ever added.
    private val nativeIsLittleEndian = true

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): NativeBuffer {
            val ptr =
                malloc(size.convert())?.reinterpret<ByteVar>()
                    ?: throw OutOfMemoryError("Failed to allocate $size bytes")
            return NativeBuffer(ptr, size, byteOrder)
        }
    }

    private fun checkOpen() = check(!closed) { "Buffer closed" }

    override fun position(): Int = positionValue

    override fun position(newPosition: Int) {
        positionValue = newPosition
    }

    override fun limit(): Int = limitValue

    override fun setLimit(limit: Int) {
        limitValue = limit
    }

    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun resetForWrite() {
        positionValue = 0
        limitValue = capacity
    }

    // === Read operations ===

    override fun readByte(): Byte {
        checkOpen()
        return ptr[positionValue++]
    }

    override fun get(index: Int): Byte {
        checkOpen()
        return ptr[index]
    }

    override fun readShort(): Short {
        checkOpen()
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun getShort(index: Int): Short {
        checkOpen()
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readInt(): Int {
        checkOpen()
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun getInt(index: Int): Int {
        checkOpen()
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readLong(): Long {
        checkOpen()
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    override fun getLong(index: Int): Long {
        checkOpen()
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkOpen()
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        checkOpen()
        if (charset != Charset.UTF8) {
            throw UnsupportedOperationException("NativeBuffer only supports UTF-8 charset. Got: $charset")
        }
        val result = simdutfDecodeUtf8((ptr + positionValue)!!, length)
        positionValue += length
        return result
    }

    override fun slice(): ReadBuffer {
        checkOpen()
        val sliceAddress = nativeAddress + positionValue
        return NativeBufferSlice(sliceAddress, remaining(), byteOrder, this)
    }

    // === Write operations ===

    override fun writeByte(byte: Byte): WriteBuffer {
        checkOpen()
        ptr[positionValue++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkOpen()
        ptr[index] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkOpen()
        set(positionValue, short)
        positionValue += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) short else short.reverseBytes()
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkOpen()
        set(positionValue, int)
        positionValue += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) int else int.reverseBytes()
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkOpen()
        set(positionValue, long)
        positionValue += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) long else long.reverseBytes()
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkOpen()
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        checkOpen()
        val size = buffer.remaining()

        // Zero-copy path: check if source has native memory access
        val srcNative = buffer.nativeMemoryAccess
        if (srcNative != null) {
            UnsafeMemory.copyMemory(
                srcNative.nativeAddress + buffer.position(),
                nativeAddress + positionValue,
                size.toLong(),
            )
            positionValue += size
            buffer.position(buffer.position() + size)
            return
        }

        // Zero-copy path: check if source has managed array access
        val srcManaged = buffer.managedMemoryAccess
        if (srcManaged != null) {
            UnsafeMemory.copyMemoryFromArray(
                srcManaged.backingArray,
                srcManaged.arrayOffset + buffer.position(),
                nativeAddress + positionValue,
                size,
            )
            positionValue += size
            buffer.position(buffer.position() + size)
            return
        }

        // Fallback: copy via temporary array
        writeBytes(buffer.readByteArray(size))
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        checkOpen()
        if (charset != Charset.UTF8) {
            throw UnsupportedOperationException("NativeBuffer only supports UTF-8 charset. Got: $charset")
        }
        val str = text.toString()
        val len = str.length
        if (len == 0) return this
        // SIMD-accelerated UTF-16->UTF-8 conversion via simdutf.
        // toCharArray() copies the String's chars, then simdutf converts directly into native memory.
        // ~28x faster than the per-character loop for large strings (518ms -> 18ms at 16MB).
        val chars = str.toCharArray()
        val dstAddr = nativeAddress + positionValue
        val written =
            chars.usePinned { pinned ->
                buf_simdutf_convert_utf16le_to_utf8(
                    pinned.addressOf(0).reinterpret(),
                    len.convert(),
                    dstAddr.toCPointer()!!,
                ).toInt()
            }
        positionValue += written
        return this
    }

    // === Optimized bulk operations ===

    /**
     * SIMD-optimized XOR mask using buf_xor_mask.
     */
    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        checkOpen()
        if (mask == 0) return
        val size = remaining()
        if (size == 0) return
        // The mask Int is big-endian (byte 0 = MSB). Native memory is little-endian,
        // so reverseBytes() ensures mask_bytes[0] from memcpy matches the first byte to XOR.
        val nativeMask = mask.reverseBytes().toUInt()
        buf_xor_mask(
            (ptr + positionValue)!!.reinterpret<UByteVar>(),
            size.convert(),
            nativeMask,
            maskOffset.toULong(),
        )
    }

    /**
     * SIMD-optimized fused copy + XOR mask using buf_xor_mask_copy.
     */
    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        checkOpen()
        val size = source.remaining()
        if (size == 0) return
        if (mask == 0) {
            write(source)
            return
        }

        val srcNative = source.nativeMemoryAccess
        if (srcNative != null) {
            val nativeMask = mask.reverseBytes().toUInt()
            buf_xor_mask_copy(
                (srcNative.nativeAddress + source.position()).toCPointer<UByteVar>(),
                (ptr + positionValue)!!.reinterpret<UByteVar>(),
                size.convert(),
                nativeMask,
                maskOffset.toULong(),
            )
            positionValue += size
            source.position(source.position() + size)
            return
        }

        // Fallback: use default byte-at-a-time implementation
        super.xorMaskCopy(source, mask, maskOffset)
    }

    /**
     * Optimized fill using memset.
     */
    override fun fill(value: Byte): WriteBuffer {
        checkOpen()
        val count = remaining()
        if (count == 0) return this
        memset(ptr + positionValue, value.toInt(), count.convert())
        positionValue += count
        return this
    }

    /**
     * Optimized contentEquals using memcmp.
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        checkOpen()
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        val otherNative = other.nativeMemoryAccess
        if (otherNative != null) {
            return memcmp(
                ptr + positionValue,
                otherNative.nativeAddress.toCPointer<ByteVar>()!! + other.position(),
                size.convert(),
            ) == 0
        }
        val otherManaged = other.managedMemoryAccess
        if (otherManaged != null) {
            return otherManaged.backingArray.usePinned { pinned ->
                memcmp(
                    ptr + positionValue,
                    pinned.addressOf(otherManaged.arrayOffset + other.position()),
                    size.convert(),
                ) == 0
            }
        }
        return super.contentEquals(other)
    }

    /**
     * SIMD-optimized mismatch using buf_mismatch.
     */
    override fun mismatch(other: ReadBuffer): Int {
        checkOpen()
        val thisRemaining = remaining()
        val otherRemaining = other.remaining()
        val minLength = minOf(thisRemaining, otherRemaining)

        if (minLength == 0) {
            return if (thisRemaining != otherRemaining) 0 else -1
        }

        val otherNative = other.nativeMemoryAccess
        val otherManaged = other.managedMemoryAccess
        val result =
            if (otherNative != null) {
                buf_mismatch(
                    (ptr + positionValue)!!.reinterpret(),
                    (otherNative.nativeAddress.toCPointer<ByteVar>()!! + other.position())!!.reinterpret(),
                    minLength.convert(),
                ).toInt()
            } else if (otherManaged != null) {
                otherManaged.backingArray.usePinned { pinned ->
                    buf_mismatch(
                        (ptr + positionValue)!!.reinterpret(),
                        pinned.addressOf(otherManaged.arrayOffset + other.position())!!.reinterpret(),
                        minLength.convert(),
                    ).toInt()
                }
            } else {
                return super.mismatch(other)
            }

        if (result != -1) return result
        return if (thisRemaining != otherRemaining) minLength else -1
    }

    /**
     * Optimized indexOf(Byte) using memchr.
     */
    override fun indexOf(byte: Byte): Int {
        checkOpen()
        val size = remaining()
        if (size == 0) return -1

        val result = memchr(ptr + positionValue, byte.toInt(), size.convert())
        return if (result == null) {
            -1
        } else {
            @Suppress("UNCHECKED_CAST")
            ((result as CPointer<ByteVar>).toLong() - (ptr + positionValue).toLong()).toInt()
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
        checkOpen()
        val size = remaining()
        if (size < 2) return -1
        val nativeValue = if (littleEndian == nativeIsLittleEndian) value else value.reverseBytes()
        val fn = if (aligned) ::buf_indexof_short_aligned else ::buf_indexof_short
        return fn(
            (ptr + positionValue)!!.reinterpret(),
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
        checkOpen()
        val size = remaining()
        if (size < 4) return -1
        val nativeValue = if (littleEndian == nativeIsLittleEndian) value else value.reverseBytes()
        val fn = if (aligned) ::buf_indexof_int_aligned else ::buf_indexof_int
        return fn(
            (ptr + positionValue)!!.reinterpret(),
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
        checkOpen()
        val size = remaining()
        if (size < 8) return -1
        val nativeValue = if (littleEndian == nativeIsLittleEndian) value else value.reverseBytes()
        val fn = if (aligned) ::buf_indexof_long_aligned else ::buf_indexof_long
        return fn(
            (ptr + positionValue)!!.reinterpret(),
            size.convert(),
            nativeValue.toULong(),
        ).toInt()
    }

    override fun freeNativeMemory() {
        if (!closed) {
            closed = true
            free(ptr)
        }
    }

    override suspend fun close() = freeNativeMemory()

    fun isClosed(): Boolean = closed
}

/**
 * Read-only slice of a NativeBuffer sharing parent's memory.
 */
private class NativeBufferSlice(
    private val baseAddress: Long,
    val capacity: Int,
    override val byteOrder: ByteOrder,
    private val parent: NativeBuffer,
) : ReadBuffer,
    NativeMemoryAccess {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity

    override val nativeAddress: Long get() = baseAddress
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    // All current Kotlin/Native targets (ARM64, x86_64) are little-endian.
    // This would need updating if a big-endian target (e.g. s390x) is ever added.
    private val nativeIsLittleEndian = true

    private fun checkOpen() = check(!parent.isClosed()) { "Parent buffer closed" }

    override fun position(): Int = positionValue

    override fun position(newPosition: Int) {
        positionValue = newPosition
    }

    override fun limit(): Int = limitValue

    override fun setLimit(limit: Int) {
        limitValue = limit
    }

    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun readByte(): Byte {
        checkOpen()
        return UnsafeMemory.getByte(baseAddress + positionValue++)
    }

    override fun get(index: Int): Byte {
        checkOpen()
        return UnsafeMemory.getByte(baseAddress + index)
    }

    override fun readShort(): Short {
        checkOpen()
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun getShort(index: Int): Short {
        checkOpen()
        val raw = UnsafeMemory.getShort(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readInt(): Int {
        checkOpen()
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun getInt(index: Int): Int {
        checkOpen()
        val raw = UnsafeMemory.getInt(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readLong(): Long {
        checkOpen()
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    override fun getLong(index: Int): Long {
        checkOpen()
        val raw = UnsafeMemory.getLong(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkOpen()
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(baseAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        checkOpen()
        if (charset != Charset.UTF8) {
            throw UnsupportedOperationException("NativeBuffer only supports UTF-8 charset. Got: $charset")
        }
        val ptr = (baseAddress + positionValue).toCPointer<ByteVar>()!!
        val result = simdutfDecodeUtf8(ptr, length)
        positionValue += length
        return result
    }

    override fun slice(): ReadBuffer {
        checkOpen()
        return NativeBufferSlice(baseAddress + positionValue, remaining(), byteOrder, parent)
    }
}

/**
 * Decodes UTF-8 bytes from a native pointer to a String using simdutf SIMD acceleration.
 * Zero-copy from native memory: ptr -> CharArray -> String (no intermediate ByteArray).
 */
private fun simdutfDecodeUtf8(
    ptr: CPointer<ByteVar>,
    length: Int,
): String {
    if (length == 0) return ""
    // Validate UTF-8 before conversion -- simdutf silently replaces invalid sequences
    if (buf_simdutf_validate_utf8(ptr, length.convert()) == 0) {
        throw IllegalArgumentException("Invalid UTF-8 sequence")
    }
    val utf16Len = buf_simdutf_utf16_length_from_utf8(ptr, length.convert()).toInt()
    if (utf16Len == 0) return ""
    val charArray = CharArray(utf16Len)
    val written =
        charArray.usePinned { pinned ->
            buf_simdutf_convert_utf8_to_chararray(
                ptr,
                length.convert(),
                pinned.addressOf(0).reinterpret<ShortVar>(),
            ).toInt()
        }
    return if (written > 0) charArray.concatToString(0, written) else ""
}
