@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.bytebuffer

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@ExperimentalUnsignedTypes
data class JvmBuffer(val byteBuffer: ByteBuffer, val fileRef: RandomAccessFile? = null) : PlatformBuffer {

    override fun resetForRead() {
        byteBuffer.flip()
    }

    override fun resetForWrite() {
        byteBuffer.clear()
    }

    override fun setLimit(limit: Int) {
        byteBuffer.limit(limit)
    }

    override val capacity = byteBuffer.capacity().toUInt()

    override fun readByte() = byteBuffer.get()
    override fun readByteArray(size: UInt) = byteBuffer.toArray(size)

    override fun readUnsignedByte() = readByte().toUByte()

    override fun readUnsignedShort() = byteBuffer.short.toUShort()

    override fun readUnsignedInt() = byteBuffer.int.toUInt()
    override fun readLong() = byteBuffer.long

    override fun readUtf8(bytes: UInt): CharSequence {
        val finalPosition = byteBuffer.position() + bytes.toInt()
        val readBuffer = byteBuffer.asReadOnlyBuffer()
        readBuffer.limit(finalPosition)
        val decoded = Charsets.UTF_8.decode(readBuffer)
        byteBuffer.position(finalPosition)
        return decoded
    }

    override fun put(buffer: PlatformBuffer) {
        byteBuffer.put((buffer as JvmBuffer).byteBuffer)
    }

    override fun write(byte: Byte): WriteBuffer {
        byteBuffer.put(byte)
        return this
    }

    override fun write(bytes: ByteArray): WriteBuffer {
        byteBuffer.put(bytes)
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

    override fun write(buffer: PlatformBuffer) {
        byteBuffer.put((buffer as JvmBuffer).byteBuffer)
    }

    override fun position(newPosition: Int) {
        byteBuffer.position(newPosition)
    }

    override fun toString() = byteBuffer.toString()

    override suspend fun close() {
        fileRef?.aClose()
    }

    override fun limit() = byteBuffer.limit().toUInt()
    override fun position() = byteBuffer.position().toUInt()
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


fun ByteBuffer.toArray(size: UInt = remaining().toUInt()): ByteArray {
    return if (hasArray()) {
        val result = ByteArray(size.toInt())
        System.arraycopy(this.array(), position(), result, 0, size.toInt())
        result
    } else {
        val byteArray = ByteArray(size.toInt())
        get(byteArray)
        byteArray
    }
}