@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

@ExperimentalUnsignedTypes
data class NativeBuffer(
    val data: ByteArray,
    private var position: Int = 0,
    private var limit: Int = data.size,
    override val capacity: UInt = data.size.toUInt(),
) : PlatformBuffer {

    override fun put(buffer: PlatformBuffer) {
        write(buffer)
    }

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun resetForWrite() {
        position = 0
        limit = data.size
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte() = data[position++]

    override fun readByteArray(size: UInt): ByteArray {
        val result = data.copyOfRange(position, position + size.toInt())
        position += size.toInt()

        return result
    }

    override fun readUnsignedByte() = data[position++].toUByte()

    override fun readUnsignedShort(): UShort {
        val value = ((0xff and data[position + 0].toInt() shl 8)
                or (0xff and data[position + 1].toInt() shl 0)).toUShort()
        position += UShort.SIZE_BYTES
        return value
    }

    override fun readUnsignedInt(): UInt {
        val value = (0xff and data[position + 0].toInt() shl 24
                or (0xff and data[position + 1].toInt() shl 16)
                or (0xff and data[position + 2].toInt() shl 8)
                or (0xff and data[position + 3].toInt() shl 0)).toUInt()
        position += UInt.SIZE_BYTES
        return value
    }

    override fun readLong(): Long {
        val value = (data[position + 0].toLong() shl 56
                or (data[position + 1].toLong() and 0xff shl 48)
                or (data[position + 2].toLong() and 0xff shl 40)
                or (data[position + 3].toLong() and 0xff shl 32)
                or (data[position + 4].toLong() and 0xff shl 24)
                or (data[position + 5].toLong() and 0xff shl 16)
                or (data[position + 6].toLong() and 0xff shl 8)
                or (data[position + 7].toLong() and 0xff))
        position += Long.SIZE_BYTES
        return value
    }

    override fun readUtf8(bytes: UInt): CharSequence {
        val value = data.decodeToString(position, position + bytes.toInt())
        position += bytes.toInt()
        return value
    }

    override fun write(byte: Byte): WriteBuffer {
        data[position++] = byte
        return this
    }

    override fun write(bytes: ByteArray): WriteBuffer {
        bytes.copyInto(data, position)
        position += bytes.size
        return this
    }

    override fun write(uByte: UByte) = write(uByte.toByte())

    override fun write(uShort: UShort): WriteBuffer {
        val value = uShort.toShort().toInt()
        data[position++] = (value shr 8 and 0xff).toByte()
        data[position++] = (value shr 0 and 0xff).toByte()
        return this
    }

    override fun write(uInt: UInt): WriteBuffer {
        val value = uInt.toInt()
        data[position++] = (value shr 24 and 0xff).toByte()
        data[position++] = (value shr 16 and 0xff).toByte()
        data[position++] = (value shr 8 and 0xff).toByte()
        data[position++] = (value shr 0 and 0xff).toByte()
        return this
    }

    override fun write(long: Long): WriteBuffer {
        val value = long
        data[position++] = (value shr 56 and 0xff).toByte()
        data[position++] = (value shr 48 and 0xff).toByte()
        data[position++] = (value shr 40 and 0xff).toByte()
        data[position++] = (value shr 32 and 0xff).toByte()
        data[position++] = (value shr 24 and 0xff).toByte()
        data[position++] = (value shr 16 and 0xff).toByte()
        data[position++] = (value shr 8 and 0xff).toByte()
        data[position++] = (value shr 0 and 0xff).toByte()
        return this
    }

    override fun write(buffer: PlatformBuffer) {
        val start = position()
        write((buffer as NativeBuffer).data)
        buffer.position((position() - start).toInt())
    }

    override fun writeUtf8(text: CharSequence): WriteBuffer {
        write(text.toString().encodeToByteArray())
        return this
    }

    override suspend fun close() = Unit

    override fun limit() = limit.toUInt()
    override fun position() = position.toUInt()
    override fun position(newPosition: Int) {
        position = newPosition
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        other as NativeBuffer
        if (position != other.position) return false
        if (limit != other.limit) return false
        if (capacity != other.capacity) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = position.hashCode()
        result = 31 * result + limit.hashCode()
        result = 31 * result + capacity.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}