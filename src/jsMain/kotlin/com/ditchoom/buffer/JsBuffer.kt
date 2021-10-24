@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_OVERRIDE")

package com.ditchoom.buffer

import org.khronos.webgl.*
import kotlin.experimental.and

data class JsBuffer(val buffer: Uint8Array) : PlatformBuffer {
    private val littleEndian = false // network endian is big endian
    override val capacity: UInt = buffer.byteLength.toUInt()
    private var limit = 0
    private var position = 0

    init {
        limit = buffer.length
    }

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun resetForWrite() {
        position = 0
        limit = capacity.toInt()
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    fun setPosition(position: Int) {
        this.position = position
    }

    override fun readByte(): Byte {
        val dataView = DataView(buffer.buffer, position++, 1)
        return dataView.getInt8(0)
    }

    override fun readByteArray(size: UInt): ByteArray {
        val byteArray = Int8Array(buffer.buffer, position, size.toInt()).unsafeCast<ByteArray>()
        position += size.toInt()
        return byteArray
    }

    override fun readUnsignedByte() = readByte().toUByte()

    override fun readUnsignedShort(): UShort {
        val dataView = DataView(buffer.buffer, position, UShort.SIZE_BYTES)
        position += UShort.SIZE_BYTES
        return dataView.getUint16(0, littleEndian).toInt().toUShort()
    }

    override fun readUnsignedInt(): UInt {
        val dataView = DataView(buffer.buffer, position, UInt.SIZE_BYTES)
        position += UInt.SIZE_BYTES
        return dataView.getUint32(0, littleEndian).toUInt()
    }

    override fun readLong(): Long {
        val long = readByteArray(Long.SIZE_BYTES.toUInt()).toLong()
        position += ULong.SIZE_BYTES
        return long
    }


    private fun ByteArray.toLong(): Long {
        var result: Long = 0
        (0 until Long.SIZE_BYTES).forEach {
            result = result shl Long.SIZE_BYTES
            result = result or ((this[it] and 0xFF.toByte()).toLong())
        }
        return result
    }

    override fun readUtf8(bytes: UInt): CharSequence {
        return readByteArray(bytes).decodeToString()
    }

    override fun put(buffer: PlatformBuffer) {
        val otherBuffer = (buffer as JsBuffer)
        val size = otherBuffer.limit - otherBuffer.position
        this.buffer.set(otherBuffer.buffer, position)
        position += size
    }

    override fun write(byte: Byte): WriteBuffer {
        val dataView = DataView(buffer.buffer, position++, 1)
        dataView.setInt8(0, byte)
        return this
    }

    override fun write(bytes: ByteArray): WriteBuffer {
        val int8Array = bytes.unsafeCast<Int8Array>()
        val uint8Array = Uint8Array(int8Array.buffer)
        this.buffer.set(uint8Array, position)
        position += uint8Array.length
        return this
    }

    override fun write(uByte: UByte): WriteBuffer {
        buffer[position++] = uByte.toByte()
        return this
    }

    override fun write(uShort: UShort): WriteBuffer {
        val dataView = DataView(buffer.buffer, position, UShort.SIZE_BYTES)
        position += UShort.SIZE_BYTES
        dataView.setUint16(0, uShort.toShort(), littleEndian)
        return this
    }

    override fun write(uInt: UInt): WriteBuffer {
        val dataView = DataView(buffer.buffer, position, UInt.SIZE_BYTES)
        position += UInt.SIZE_BYTES
        dataView.setUint32(0, uInt.toInt(), littleEndian)
        return this
    }

    override fun write(long: Long): WriteBuffer {
        write(long.toByteArray())
        return this
    }

    private fun Long.toByteArray(): ByteArray {
        var l = this
        val result = ByteArray(8)
        for (i in 7 downTo 0) {
            result[i] = (l and 0xFF).toByte()
            l = l shr 8
        }
        return result
    }

    override fun writeUtf8(text: CharSequence): WriteBuffer {
        write(text.toString().encodeToByteArray())
        return this
    }

    override fun limit() = limit.toUInt()
    override fun position() = position.toUInt()
    override fun position(newPosition: Int) {
        position = newPosition
    }

    override fun write(buffer: PlatformBuffer) {
        val otherRemaining = buffer.remaining()
        this.buffer.set((buffer as JsBuffer).buffer, position)
        position += otherRemaining.toInt()
    }

    override suspend fun close() {}
}