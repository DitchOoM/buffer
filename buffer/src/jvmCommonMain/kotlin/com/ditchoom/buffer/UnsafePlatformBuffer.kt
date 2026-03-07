package com.ditchoom.buffer

/**
 * A PlatformBuffer backed by Unsafe-allocated native memory with deterministic cleanup.
 *
 * Used on JVM 8 and Android where Unsafe.invokeCleaner is not available.
 * Implements [CloseableBuffer] — callers must call [freeNativeMemory] or use `buffer.use {}`.
 */
abstract class UnsafePlatformBuffer(
    override val nativeAddress: Long,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : PlatformBuffer,
    NativeMemoryAccess,
    CloseableBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private var freed = false
    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    override val isFreed: Boolean get() = freed
    override val nativeSize: Long get() = capacity.toLong()

    private fun checkNotFreed() {
        if (freed) throw IllegalStateException("Buffer has been freed")
    }

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            UnsafeAllocator.freeMemory(nativeAddress)
        }
    }

    // Position and limit management
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

    // Read operations (relative)
    override fun readByte(): Byte {
        checkNotFreed()
        return UnsafeMemory.getByte(nativeAddress + positionValue++)
    }

    override fun readShort(): Short {
        checkNotFreed()
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun readInt(): Int {
        checkNotFreed()
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun readLong(): Long {
        checkNotFreed()
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte {
        checkNotFreed()
        return UnsafeMemory.getByte(nativeAddress + index)
    }

    override fun getShort(index: Int): Short {
        checkNotFreed()
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Short.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Short.reverseBytes(raw) else raw
        }
    }

    override fun getInt(index: Int): Int {
        checkNotFreed()
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Integer.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Integer.reverseBytes(raw) else raw
        }
    }

    override fun getLong(index: Int): Long {
        checkNotFreed()
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Long.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Long.reverseBytes(raw) else raw
        }
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        checkNotFreed()
        UnsafeMemory.putByte(nativeAddress + positionValue++, byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkNotFreed()
        set(positionValue, short)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkNotFreed()
        set(positionValue, int)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkNotFreed()
        set(positionValue, long)
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkNotFreed()
        UnsafeMemory.putByte(nativeAddress + index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkNotFreed()
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) short else java.lang.Short.reverseBytes(short)
            } else {
                if (littleEndian) java.lang.Short.reverseBytes(short) else short
            }
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkNotFreed()
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) int else java.lang.Integer.reverseBytes(int)
            } else {
                if (littleEndian) java.lang.Integer.reverseBytes(int) else int
            }
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkNotFreed()
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) long else java.lang.Long.reverseBytes(long)
            } else {
                if (littleEndian) java.lang.Long.reverseBytes(long) else long
            }
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        checkNotFreed()
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotFreed()
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        checkNotFreed()
        val size = buffer.remaining()
        writeBytes(buffer.readByteArray(size))
    }

    override fun slice(): ReadBuffer {
        checkNotFreed()
        return UnsafePlatformBufferSlice(nativeAddress + positionValue, remaining(), byteOrder)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        checkNotFreed()
        val bytes = readByteArray(length)
        return String(bytes, charset.toJavaCharset())
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        checkNotFreed()
        val bytes = text.toString().toByteArray(charset.toJavaCharset())
        writeBytes(bytes)
        return this
    }

    private fun Charset.toJavaCharset(): java.nio.charset.Charset =
        when (this) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 ->
                java.nio.charset.Charset
                    .forName("UTF-32")
            Charset.UTF32BigEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32BE")
            Charset.UTF32LittleEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32LE")
        }

    companion object
}

/**
 * Non-closeable slice view of a UnsafePlatformBuffer.
 * The parent buffer owns the memory and is responsible for freeing it.
 */
private class UnsafePlatformBufferSlice(
    override val nativeAddress: Long,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ReadWriteBuffer,
    NativeMemoryAccess {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    override val nativeSize: Long get() = capacity.toLong()

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

    override fun readByte(): Byte = UnsafeMemory.getByte(nativeAddress + positionValue++)

    override fun get(index: Int): Byte = UnsafeMemory.getByte(nativeAddress + index)

    override fun getShort(index: Int): Short {
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Short.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Short.reverseBytes(raw) else raw
        }
    }

    override fun getInt(index: Int): Int {
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Integer.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Integer.reverseBytes(raw) else raw
        }
    }

    override fun getLong(index: Int): Long {
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Long.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Long.reverseBytes(raw) else raw
        }
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        UnsafeMemory.putByte(nativeAddress + positionValue++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        UnsafeMemory.putByte(nativeAddress + index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) short else java.lang.Short.reverseBytes(short)
            } else {
                if (littleEndian) java.lang.Short.reverseBytes(short) else short
            }
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) int else java.lang.Integer.reverseBytes(int)
            } else {
                if (littleEndian) java.lang.Integer.reverseBytes(int) else int
            }
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) long else java.lang.Long.reverseBytes(long)
            } else {
                if (littleEndian) java.lang.Long.reverseBytes(long) else long
            }
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    override fun readByteArray(size: Int): ByteArray {
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        writeBytes(buffer.readByteArray(size))
    }

    override fun slice(): ReadBuffer = UnsafePlatformBufferSlice(nativeAddress + positionValue, remaining(), byteOrder)

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return String(bytes, charset.toJavaCharset())
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val bytes = text.toString().toByteArray(charset.toJavaCharset())
        writeBytes(bytes)
        return this
    }

    private fun Charset.toJavaCharset(): java.nio.charset.Charset =
        when (this) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 ->
                java.nio.charset.Charset
                    .forName("UTF-32")
            Charset.UTF32BigEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32BE")
            Charset.UTF32LittleEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32LE")
        }
}
