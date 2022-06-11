
package com.ditchoom.buffer

import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class BaseJvmBuffer(val byteBuffer: ByteBuffer, val fileRef: RandomAccessFile? = null) : PlatformBuffer {
    override val byteOrder = when (byteBuffer.order()) {
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
    override fun readByteArray(size: Int) = byteBuffer.toArray(size)

    override fun slice() = JvmBuffer(byteBuffer.slice())

    override fun readUnsignedByte() = readByte().toUByte()

    override fun readUnsignedShort() = byteBuffer.short.toUShort()

    override fun readUnsignedInt() = byteBuffer.int.toUInt()
    override fun readLong() = byteBuffer.long

    override fun readUtf8(bytes: Int): CharSequence {
        val finalPosition = buffer.position() + bytes
        val readBuffer = byteBuffer.asReadOnlyBuffer()
        (readBuffer as Buffer).limit(finalPosition)
        val decoded = Charsets.UTF_8.decode(readBuffer)
        buffer.position(finalPosition)
        return decoded
    }

    override fun write(byte: Byte): WriteBuffer {
        byteBuffer.put(byte)
        return this
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        byteBuffer.put(bytes, offset, length)
        return this
    }

    override fun write(uByte: UByte): WriteBuffer {
        byteBuffer.put(uByte.toByte())
        return this
    }

    override fun write(uShort: UShort): WriteBuffer {
        byteBuffer.putShort(uShort.toShort())
        return this
    }

    override fun write(uInt: UInt): WriteBuffer {
        byteBuffer.putInt(uInt.toInt())
        return this
    }

    override fun write(long: Long): WriteBuffer {
        byteBuffer.putLong(long)
        return this
    }

    override fun writeUtf8(text: CharSequence): WriteBuffer {
        write(text.toString().encodeToByteArray())
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

    override fun toString() = "Buffer[pos=${position()} lim=${limit()} cap=${capacity}]"

    override suspend fun close() {
        fileRef?.aClose()
    }

    override fun limit() = buffer.limit()
    override fun position() = buffer.position()
}


suspend fun RandomAccessFile.aClose() = suspendCoroutine<Unit> {
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
        System.arraycopy(this.array(), position(), result, 0, size)
        result
    } else {
        val byteArray = ByteArray(size)
        get(byteArray)
        byteArray
    }
}