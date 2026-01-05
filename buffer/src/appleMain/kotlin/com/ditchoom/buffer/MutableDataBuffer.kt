package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.NSString
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytesNoCopy
import platform.Foundation.replaceBytesInRange
import platform.posix.memcpy

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
class MutableDataBuffer(
    dataRef: NSData,
    override val byteOrder: ByteOrder,
    private val internalBackingArray: ByteArray? = null,
) : DataBuffer(dataRef, byteOrder),
    PlatformBuffer,
    NativeMemoryAccess,
    ManagedMemoryAccess {
    val mutableData = dataRef as? NSMutableData

    @Suppress("UNCHECKED_CAST")
    private val mutableBytePointer = mutableData?.mutableBytes as? CPointer<ByteVar>

    init {
        check(
            (mutableBytePointer != null && internalBackingArray == null) ||
                (mutableBytePointer == null && internalBackingArray != null),
        )
    }

    /**
     * The native memory address for C interop.
     * Only available for buffers backed by NSMutableData (not wrapped ByteArrays).
     *
     * @throws UnsupportedOperationException if this buffer wraps a ByteArray
     */
    override val nativeAddress: Long
        get() = mutableBytePointer?.toLong()
            ?: throw UnsupportedOperationException(
                "Native memory access not available for wrapped ByteArray buffers. " +
                    "Use PlatformBuffer.allocate() instead of wrap() for native access.",
            )

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Int get() = capacity

    /**
     * The underlying ByteArray backing this buffer.
     * Only available for buffers created via wrap() (not allocate()).
     *
     * @throws UnsupportedOperationException if this buffer is backed by NSMutableData
     */
    override val backingArray: ByteArray
        get() = internalBackingArray
            ?: throw UnsupportedOperationException(
                "Managed memory access not available for NSMutableData-backed buffers. " +
                    "Use PlatformBuffer.wrap() instead of allocate() for ByteArray access.",
            )

    /**
     * The offset within [backingArray] where this buffer's data begins.
     */
    override val arrayOffset: Int get() = 0

    private fun writeByteInternal(
        index: Int,
        byte: Byte,
    ) {
        internalBackingArray?.set(index, byte)
        mutableBytePointer?.set(index, byte)
    }

    override fun resetForWrite() {
        position = 0
        limit = data.length.toInt()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        writeByteInternal(position++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        writeByteInternal(index, byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) short.reverseBytes() else short
        mutableBytePointer?.let { ptr ->
            (ptr + position)!!.reinterpret<ShortVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[position] = value.toByte()
            arr[position + 1] = (value.toInt() shr 8).toByte()
        }
        position += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) short.reverseBytes() else short
        mutableBytePointer?.let { ptr ->
            (ptr + index)!!.reinterpret<ShortVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[index] = value.toByte()
            arr[index + 1] = (value.toInt() shr 8).toByte()
        }
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) int.reverseBytes() else int
        mutableBytePointer?.let { ptr ->
            (ptr + position)!!.reinterpret<IntVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[position] = value.toByte()
            arr[position + 1] = (value shr 8).toByte()
            arr[position + 2] = (value shr 16).toByte()
            arr[position + 3] = (value shr 24).toByte()
        }
        position += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) int.reverseBytes() else int
        mutableBytePointer?.let { ptr ->
            (ptr + index)!!.reinterpret<IntVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[index] = value.toByte()
            arr[index + 1] = (value shr 8).toByte()
            arr[index + 2] = (value shr 16).toByte()
            arr[index + 3] = (value shr 24).toByte()
        }
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) long.reverseBytes() else long
        mutableBytePointer?.let { ptr ->
            (ptr + position)!!.reinterpret<LongVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[position] = value.toByte()
            arr[position + 1] = (value shr 8).toByte()
            arr[position + 2] = (value shr 16).toByte()
            arr[position + 3] = (value shr 24).toByte()
            arr[position + 4] = (value shr 32).toByte()
            arr[position + 5] = (value shr 40).toByte()
            arr[position + 6] = (value shr 48).toByte()
            arr[position + 7] = (value shr 56).toByte()
        }
        position += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value = if (byteOrder == ByteOrder.BIG_ENDIAN) long.reverseBytes() else long
        mutableBytePointer?.let { ptr ->
            (ptr + index)!!.reinterpret<LongVar>()[0] = value
        } ?: internalBackingArray?.let { arr ->
            arr[index] = value.toByte()
            arr[index + 1] = (value shr 8).toByte()
            arr[index + 2] = (value shr 16).toByte()
            arr[index + 3] = (value shr 24).toByte()
            arr[index + 4] = (value shr 32).toByte()
            arr[index + 5] = (value shr 40).toByte()
            arr[index + 6] = (value shr 48).toByte()
            arr[index + 7] = (value shr 56).toByte()
        }
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
        if (mutableData != null) {
            val range = NSMakeRange(position.convert(), length.convert())
            bytes.usePinned { pin ->
                mutableData.replaceBytesInRange(range, pin.addressOf(offset))
            }
            position += length
        } else if (internalBackingArray != null) {
            bytes.copyInto(internalBackingArray, position, offset, offset + length)
            position += length
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        if (buffer is DataBuffer && mutableBytePointer != null) {
            val bytesToCopySize = buffer.remaining()
            // Direct memory copy - no intermediate allocation
            val srcPtr = buffer.bytePointer + buffer.position()
            val dstPtr = mutableBytePointer + position
            memcpy(dstPtr, srcPtr, bytesToCopySize.convert())
            position += bytesToCopySize
            buffer.position(buffer.position() + bytesToCopySize)
        } else {
            val remainingByteArray = buffer.readByteArray(buffer.remaining())
            writeBytes(remainingByteArray)
        }
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val string =
            if (text is String) {
                text as NSString
            } else {
                @Suppress("CAST_NEVER_SUCCEEDS")
                text.toString() as NSString
            }
        val charsetEncoding = charset.toEncoding()
        write(DataBuffer(string.dataUsingEncoding(charsetEncoding)!!, byteOrder))
        return this
    }

    companion object {
        fun wrap(
            byteArray: ByteArray,
            byteOrder: ByteOrder,
        ) = byteArray.useNSDataRef {
            MutableDataBuffer(it, byteOrder, byteArray)
        }
    }
}

private fun <T> ByteArray.useNSDataRef(block: (NSData) -> T): T {
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, UnsafeNumber::class)
    return usePinned { pin ->
        val bytesPointer =
            when {
                isNotEmpty() -> pin.addressOf(0)
                else -> null
            }
        val nsData =
            NSData.dataWithBytesNoCopy(
                bytes = bytesPointer,
                length = size.convert(),
                freeWhenDone = false,
            )

        @Suppress("UNCHECKED_CAST")
        val typeRef = CFBridgingRetain(nsData) as CFDataRef

        try {
            block(nsData)
        } finally {
            CFBridgingRelease(typeRef)
        }
    }
}
