package com.ditchoom.buffer

actual object UnsafeMemory {
    private var nextId = 1L
    private val buffers = mutableMapOf<Long, ByteArray>()

    actual val nativeByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

    actual fun allocate(size: Int): Long {
        val id = nextId++
        buffers[id] = ByteArray(size)
        return id
    }

    actual fun free(address: Long) {
        buffers.remove(address)
    }

    private fun getBuffer(address: Long): ByteArray = buffers[address] ?: error("Invalid address: $address")

    actual fun getByte(
        address: Long,
        offset: Int,
    ): Byte = getBuffer(address)[offset]

    actual fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    ) {
        getBuffer(address)[offset] = value
    }

    actual fun getShort(
        address: Long,
        offset: Int,
    ): Short {
        val buffer = getBuffer(address)
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        return (b0 or (b1 shl 8)).toShort()
    }

    actual fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    ) {
        val buffer = getBuffer(address)
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value.toInt() shr 8).toByte()
    }

    actual fun getInt(
        address: Long,
        offset: Int,
    ): Int {
        val buffer = getBuffer(address)
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    actual fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    ) {
        val buffer = getBuffer(address)
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
        buffer[offset + 2] = (value shr 16).toByte()
        buffer[offset + 3] = (value shr 24).toByte()
    }

    actual fun getLong(
        address: Long,
        offset: Int,
    ): Long {
        val buffer = getBuffer(address)
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        val b4 = buffer[offset + 4].toLong() and 0xFF
        val b5 = buffer[offset + 5].toLong() and 0xFF
        val b6 = buffer[offset + 6].toLong() and 0xFF
        val b7 = buffer[offset + 7].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) or
            (b4 shl 32) or (b5 shl 40) or (b6 shl 48) or (b7 shl 56)
    }

    actual fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    ) {
        val buffer = getBuffer(address)
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
        buffer[offset + 2] = (value shr 16).toByte()
        buffer[offset + 3] = (value shr 24).toByte()
        buffer[offset + 4] = (value shr 32).toByte()
        buffer[offset + 5] = (value shr 40).toByte()
        buffer[offset + 6] = (value shr 48).toByte()
        buffer[offset + 7] = (value shr 56).toByte()
    }

    actual fun getFloat(
        address: Long,
        offset: Int,
    ): Float = Float.fromBits(getInt(address, offset))

    actual fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    ) {
        putInt(address, offset, value.toRawBits())
    }

    actual fun getDouble(
        address: Long,
        offset: Int,
    ): Double = Double.fromBits(getLong(address, offset))

    actual fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    ) {
        putLong(address, offset, value.toRawBits())
    }

    actual fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        val buffer = getBuffer(address)
        buffer.copyInto(dest, destOffset, offset, offset + length)
    }

    actual fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val buffer = getBuffer(address)
        src.copyInto(buffer, offset, srcOffset, srcOffset + length)
    }

    actual fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val buffer = getBuffer(address)
        buffer.fill(0, offset, offset + length)
    }
}
