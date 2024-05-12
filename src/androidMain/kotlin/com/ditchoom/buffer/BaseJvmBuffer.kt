package com.ditchoom.buffer

import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class BaseJvmBuffer(open val byteBuffer: ByteBuffer, val fileRef: RandomAccessFile? = null) :
    PlatformBuffer {
    override val byteOrder =
        when (byteBuffer.order()) {
            java.nio.ByteOrder.BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
            java.nio.ByteOrder.LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
            else -> ByteOrder.BIG_ENDIAN
        }

    // Use Buffer reference to avoid NoSuchMethodException between JVM. see https://stackoverflow.com/q/61267495
    private val buffer = byteBuffer as Buffer

    override fun resetForRead() {
        buffer.flip()
    }

    override fun resetForWrite() {
        buffer.clear()
    }

    override fun setLimit(limit: Int) {
        buffer.limit(limit)
    }

    override val capacity = buffer.capacity()

    override fun readByte() = byteBuffer.get()

    override fun get(index: Int): Byte = byteBuffer.get(index)

    override fun readByteArray(size: Int) = byteBuffer.toArray(size)

    override fun slice() = JvmBuffer(byteBuffer.slice())

    override fun readShort(): Short = byteBuffer.short

    override fun getShort(index: Int): Short = byteBuffer.getShort(index)

    override fun readInt() = byteBuffer.int

    override fun getInt(index: Int): Int = byteBuffer.getInt(index)

    override fun readLong() = byteBuffer.long

    override fun getLong(index: Int): Long = byteBuffer.getLong(index)

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val finalPosition = buffer.position() + length
        val readBuffer = byteBuffer.asReadOnlyBuffer()
        (readBuffer as Buffer).limit(finalPosition)
        val charsetConverted =
            when (charset) {
                Charset.UTF8 -> Charsets.UTF_8
                Charset.UTF16 -> Charsets.UTF_16
                Charset.UTF16BigEndian -> Charsets.UTF_16BE
                Charset.UTF16LittleEndian -> Charsets.UTF_16LE
                Charset.ASCII -> Charsets.US_ASCII
                Charset.ISOLatin1 -> Charsets.ISO_8859_1
                Charset.UTF32 -> Charsets.UTF_32
                Charset.UTF32LittleEndian -> Charsets.UTF_32LE
                Charset.UTF32BigEndian -> Charsets.UTF_32BE
            }
        val decoded =
            charsetConverted.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(readBuffer)
        buffer.position(finalPosition)
        return decoded.toString()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        byteBuffer.put(byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        byteBuffer.put(index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        byteBuffer.put(bytes, offset, length)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        byteBuffer.putShort(short)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        byteBuffer.putShort(index, short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        byteBuffer.putInt(int)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        byteBuffer.putInt(index, int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        byteBuffer.putLong(long)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        byteBuffer.putLong(index, long)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val encoder = charset.toEncoder()
        encoder.reset()
        encoder.encode(CharBuffer.wrap(text), byteBuffer, true)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        if (buffer is JvmBuffer) {
            byteBuffer.put(buffer.byteBuffer)
        } else {
            byteBuffer.put(buffer.readByteArray(buffer.remaining()))
        }
    }

    override fun position(newPosition: Int) {
        buffer.position(newPosition)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PlatformBuffer) return false
        if (position() != other.position()) return false
        if (limit() != other.limit()) return false
        if (capacity != other.capacity) return false
        return true
    }

    override fun toString() = "Buffer[pos=${position()} lim=${limit()} cap=$capacity]"

    override suspend fun close() {
        fileRef?.aClose()
    }

    override fun limit() = buffer.limit()

    override fun position() = buffer.position()
}

suspend fun RandomAccessFile.aClose() =
    suspendCoroutine<Unit> {
        try {
            // TODO: fix the blocking call
            @Suppress("BlockingMethodInNonBlockingContext")
            close()
            it.resume(Unit)
        } catch (e: Throwable) {
            it.resumeWithException(e)
        }
    }

fun ByteBuffer.toArray(size: Int = remaining()): ByteArray {
    return if (hasArray()) {
        val result = ByteArray(size)
        val buffer = this as Buffer
        System.arraycopy(this.array(), buffer.arrayOffset() + buffer.position(), result, 0, size)
        buffer.position(buffer.position() + size)
        result
    } else {
        val byteArray = ByteArray(size)
        get(byteArray)
        byteArray
    }
}
