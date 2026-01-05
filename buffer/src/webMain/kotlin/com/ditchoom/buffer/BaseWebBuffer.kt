package com.ditchoom.buffer

/**
 * Base class for web platform buffers (JS and WASM).
 * Contains shared position/limit management and delegates memory access to subclasses.
 */
abstract class BaseWebBuffer(
    final override val capacity: Int,
    final override val byteOrder: ByteOrder,
) : PlatformBuffer {
    protected var positionValue: Int = 0
    protected var limitValue: Int = capacity
    protected val littleEndian: Boolean = byteOrder == ByteOrder.LITTLE_ENDIAN

    // Abstract methods - platform specific memory access
    // All subclasses must implement these for optimal performance
    protected abstract fun loadByte(index: Int): Byte

    protected abstract fun loadShort(index: Int): Short

    protected abstract fun loadInt(index: Int): Int

    protected abstract fun loadLong(index: Int): Long

    protected abstract fun storeByte(
        index: Int,
        value: Byte,
    )

    protected abstract fun storeShort(
        index: Int,
        value: Short,
    )

    protected abstract fun storeInt(
        index: Int,
        value: Int,
    )

    protected abstract fun storeLong(
        index: Int,
        value: Long,
    )

    // Position/Limit management - shared implementation
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

    // ReadBuffer implementation using abstract load methods
    override fun readByte(): Byte {
        val value = loadByte(positionValue)
        positionValue++
        return value
    }

    override fun get(index: Int): Byte = loadByte(index)

    override fun readShort(): Short {
        val value = loadShort(positionValue)
        positionValue += Short.SIZE_BYTES
        return value
    }

    override fun getShort(index: Int): Short = loadShort(index)

    override fun readInt(): Int {
        val value = loadInt(positionValue)
        positionValue += Int.SIZE_BYTES
        return value
    }

    override fun getInt(index: Int): Int = loadInt(index)

    override fun readLong(): Long {
        val value = loadLong(positionValue)
        positionValue += Long.SIZE_BYTES
        return value
    }

    override fun getLong(index: Int): Long = loadLong(index)

    // WriteBuffer implementation using abstract store methods
    override fun writeByte(byte: Byte): WriteBuffer {
        storeByte(positionValue, byte)
        positionValue++
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        storeByte(index, byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        storeShort(positionValue, short)
        positionValue += Short.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        storeShort(index, short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        storeInt(positionValue, int)
        positionValue += Int.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        storeInt(index, int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        storeLong(positionValue, long)
        positionValue += Long.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        storeLong(index, long)
        return this
    }

    override suspend fun close() {
        // Default no-op, can be overridden if cleanup is needed
    }
}
