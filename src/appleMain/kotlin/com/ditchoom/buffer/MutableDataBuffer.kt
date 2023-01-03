package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytesNoCopy
import platform.Foundation.replaceBytesInRange
import platform.Foundation.subdataWithRange

@Suppress("OPT_IN_USAGE")
data class MutableDataBuffer(
    val mutableData: NSMutableData,
    override val byteOrder: ByteOrder
) : DataBuffer(mutableData, byteOrder), PlatformBuffer {

    @Suppress("UNCHECKED_CAST")
    private val bytePointer = mutableData.mutableBytes as CPointer<ByteVar>

    override fun resetForWrite() {
        position = 0
        limit = mutableData.length.toInt()
    }


    override fun write(byte: Byte): WriteBuffer {
        bytePointer[position++] = byte
        return this
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        val range = NSMakeRange(position.convert(), length.convert())
        bytes.usePinned { pin ->
            mutableData.replaceBytesInRange(range, pin.addressOf(offset))
        }
        position += length
        return this
    }

    override fun write(uByte: UByte) = write(uByte.toByte())

    override fun write(uShort: UShort): WriteBuffer {
        val value = uShort.toShort().toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
        } else {
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
        }
        return this
    }

    override fun write(uInt: UInt): WriteBuffer {
        val value = uInt.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            bytePointer[position++] = (value shr 24 and 0xff).toByte()
            bytePointer[position++] = (value shr 16 and 0xff).toByte()
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
        } else {
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
            bytePointer[position++] = (value shr 16 and 0xff).toByte()
            bytePointer[position++] = (value shr 24 and 0xff).toByte()
        }
        return this
    }

    override fun write(long: Long): WriteBuffer {
        val value = long
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            bytePointer[position++] = (value shr 56 and 0xff).toByte()
            bytePointer[position++] = (value shr 48 and 0xff).toByte()
            bytePointer[position++] = (value shr 40 and 0xff).toByte()
            bytePointer[position++] = (value shr 32 and 0xff).toByte()
            bytePointer[position++] = (value shr 24 and 0xff).toByte()
            bytePointer[position++] = (value shr 16 and 0xff).toByte()
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
        } else {
            bytePointer[position++] = (value shr 0 and 0xff).toByte()
            bytePointer[position++] = (value shr 8 and 0xff).toByte()
            bytePointer[position++] = (value shr 16 and 0xff).toByte()
            bytePointer[position++] = (value shr 24 and 0xff).toByte()
            bytePointer[position++] = (value shr 32 and 0xff).toByte()
            bytePointer[position++] = (value shr 40 and 0xff).toByte()
            bytePointer[position++] = (value shr 48 and 0xff).toByte()
            bytePointer[position++] = (value shr 56 and 0xff).toByte()
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        if (buffer is DataBuffer) {
            val bytesToCopySize = buffer.remaining()
            val otherSubdata = buffer.data.subdataWithRange(
                NSMakeRange(
                    buffer.position().convert(),
                    bytesToCopySize.convert()
                )
            )
            mutableData.replaceBytesInRange(
                NSMakeRange(
                    position.convert(),
                    bytesToCopySize.convert()
                ), otherSubdata.bytes
            )
            position += bytesToCopySize
        } else {
            val remainingByteArray = buffer.readByteArray(buffer.remaining())
            write(remainingByteArray)
        }
    }

    override fun writeUtf8(text: CharSequence): WriteBuffer {
        val string = if (text is String) {
            text as NSString
        } else {
            @Suppress("CAST_NEVER_SUCCEEDS")
            text.toString() as NSString
        }
        write(DataBuffer(string.dataUsingEncoding(NSUTF8StringEncoding)!!, byteOrder))
        return this
    }

    companion object {
        fun wrap(byteArray: ByteArray, byteOrder: ByteOrder): MutableDataBuffer {
            val data = byteArray.usePinned {
                NSMutableData.dataWithBytesNoCopy(
                    it.addressOf(0),
                    byteArray.size.convert(),
                    false
                ) as NSMutableData
            }
            val buffer = MutableDataBuffer(data, byteOrder)
            buffer.limit = byteArray.size
            return buffer
        }
    }
}
