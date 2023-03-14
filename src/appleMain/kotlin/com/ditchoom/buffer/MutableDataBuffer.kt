package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.set
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
import platform.Foundation.subdataWithRange

@Suppress("OPT_IN_USAGE")
class MutableDataBuffer(
    dataRef: NSData,
    override val byteOrder: ByteOrder,
    private val backingArray: ByteArray? = null
) : DataBuffer(dataRef, byteOrder), PlatformBuffer {

    val mutableData = dataRef as? NSMutableData

    @Suppress("UNCHECKED_CAST")
    private val bytePointer = mutableData?.mutableBytes as? CPointer<ByteVar>

    init {
        check(
            (bytePointer != null && backingArray == null) ||
                (bytePointer == null && backingArray != null)
        )
    }

    private fun writeByteInternal(index: Int, byte: Byte) {
        backingArray?.set(index, byte)
        bytePointer?.set(index, byte)
    }

    override fun resetForWrite() {
        position = 0
        limit = data.length.toInt()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        writeByteInternal(position++, byte)
        return this
    }

    override fun set(index: Int, byte: Byte): WriteBuffer {
        writeByteInternal(position, byte)
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        if (length < 1) {
            return this
        }
        if (mutableData != null) {
            val range = NSMakeRange(position.convert(), length.convert())
            bytes.usePinned { pin ->
                mutableData.replaceBytesInRange(range, pin.addressOf(offset))
            }
            position += length
        } else if (backingArray != null) {
            bytes.copyInto(backingArray, position, offset, offset + length)
            position += length
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        if (buffer is DataBuffer && mutableData != null) {
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
                ),
                otherSubdata.bytes
            )
            position += bytesToCopySize
        } else {
            val remainingByteArray = buffer.readByteArray(buffer.remaining())
            writeBytes(remainingByteArray)
        }
    }

    override fun writeString(text: CharSequence, charset: Charset): WriteBuffer {
        val string = if (text is String) {
            text as NSString
        } else {
            @Suppress("CAST_NEVER_SUCCEEDS")
            text.toString() as NSString
        }
        val charsetEncoding = charset.toEncoding()
        charset.toEncoding()
        write(DataBuffer(string.dataUsingEncoding(charsetEncoding)!!, byteOrder))
        return this
    }

    companion object {
        fun wrap(byteArray: ByteArray, byteOrder: ByteOrder) = byteArray.useNSDataRef {
            MutableDataBuffer(it, byteOrder, byteArray)
        }
    }
}

private fun <T> ByteArray.useNSDataRef(block: (NSData) -> T): T {
    return usePinned { pin ->
        val bytesPointer = when {
            isNotEmpty() -> pin.addressOf(0)
            else -> null
        }
        @Suppress("OPT_IN_USAGE") val nsData = NSData.dataWithBytesNoCopy(
            bytes = bytesPointer,
            length = size.convert(),
            freeWhenDone = false
        )
        @Suppress("UNCHECKED_CAST") val typeRef = CFBridgingRetain(nsData) as CFDataRef
        try {
            block(nsData)
        } finally {
            CFBridgingRelease(typeRef)
        }
    }
}
