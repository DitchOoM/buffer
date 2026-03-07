package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * A buffer wrapper that returns its inner buffer to a pool when all references are released.
 *
 * Created by [BufferPool.acquire] to make pool-acquired buffers transparent.
 *
 * Uses reference counting to track outstanding slices. The inner buffer is returned
 * to the pool only when the chunk itself AND all slices created from it are released.
 * This prevents the pool from reusing memory that is still referenced by slices.
 *
 * All read/write operations throw [IllegalStateException] after [freeNativeMemory] is called,
 * preventing use-after-free bugs where the inner buffer may have been reused by another caller.
 */
internal class PooledBuffer(
    internal val inner: PlatformBuffer,
    private val pool: BufferPool,
) : PlatformBuffer by inner {
    private var freed = false
    private var refCount = 1 // 1 for the chunk reference in StreamProcessor

    private fun checkNotFreed() {
        if (freed) throw IllegalStateException("Buffer has been freed and returned to pool")
    }

    internal fun addRef() {
        refCount++
    }

    internal fun releaseRef() {
        if (--refCount == 0) {
            pool.release(inner)
        }
    }

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            releaseRef()
        }
    }

    override fun slice(): ReadBuffer {
        checkNotFreed()
        addRef()
        return TrackedSlice(inner.slice(), this)
    }

    override suspend fun close() {
        freeNativeMemory()
    }

    @Suppress("DEPRECATION")
    override fun unwrap(): PlatformBuffer {
        checkNotFreed()
        return inner.unwrap()
    }

    // ========================================================================
    // PositionBuffer
    // ========================================================================

    override val byteOrder: ByteOrder get() = inner.byteOrder

    override fun position(): Int = inner.position()

    override fun position(newPosition: Int) {
        checkNotFreed()
        inner.position(newPosition)
    }

    override fun limit(): Int = inner.limit()

    override fun setLimit(limit: Int) {
        checkNotFreed()
        inner.setLimit(limit)
    }

    // ========================================================================
    // ReadWriteBuffer
    // ========================================================================

    override val capacity: Int get() = inner.capacity

    // ========================================================================
    // ReadBuffer — relative reads
    // ========================================================================

    override fun resetForRead() {
        checkNotFreed()
        inner.resetForRead()
    }

    override fun readByte(): Byte {
        checkNotFreed()
        return inner.readByte()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkNotFreed()
        return inner.readByteArray(size)
    }

    override fun readShort(): Short {
        checkNotFreed()
        return inner.readShort()
    }

    override fun readInt(): Int {
        checkNotFreed()
        return inner.readInt()
    }

    override fun readLong(): Long {
        checkNotFreed()
        return inner.readLong()
    }

    override fun readFloat(): Float {
        checkNotFreed()
        return inner.readFloat()
    }

    override fun readDouble(): Double {
        checkNotFreed()
        return inner.readDouble()
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        checkNotFreed()
        return inner.readString(length, charset)
    }

    override fun readLine(): CharSequence {
        checkNotFreed()
        return inner.readLine()
    }

    // ========================================================================
    // ReadBuffer — absolute reads
    // ========================================================================

    override fun get(index: Int): Byte {
        checkNotFreed()
        return inner.get(index)
    }

    override fun getShort(index: Int): Short {
        checkNotFreed()
        return inner.getShort(index)
    }

    override fun getInt(index: Int): Int {
        checkNotFreed()
        return inner.getInt(index)
    }

    override fun getLong(index: Int): Long {
        checkNotFreed()
        return inner.getLong(index)
    }

    override fun getFloat(index: Int): Float {
        checkNotFreed()
        return inner.getFloat(index)
    }

    override fun getDouble(index: Int): Double {
        checkNotFreed()
        return inner.getDouble(index)
    }

    // ========================================================================
    // WriteBuffer — relative writes
    // ========================================================================

    override fun resetForWrite() {
        checkNotFreed()
        inner.resetForWrite()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkNotFreed()
        inner.writeByte(byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotFreed()
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkNotFreed()
        inner.writeShort(short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkNotFreed()
        inner.writeInt(int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkNotFreed()
        inner.writeLong(long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        checkNotFreed()
        inner.writeFloat(float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        checkNotFreed()
        inner.writeDouble(double)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        checkNotFreed()
        inner.writeString(text, charset)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        checkNotFreed()
        inner.write(buffer)
    }

    // ========================================================================
    // WriteBuffer — absolute writes
    // ========================================================================

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, short)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, int)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, long)
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, float)
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        checkNotFreed()
        inner.set(index, double)
        return this
    }

    // ========================================================================
    // ReadWriteBuffer — masking
    // ========================================================================

    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        checkNotFreed()
        inner.xorMask(mask, maskOffset)
    }

    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        checkNotFreed()
        inner.xorMaskCopy(source, mask, maskOffset)
    }

    // ========================================================================
    // ReadBuffer — search & comparison (delegate to inner for optimized impls)
    // ========================================================================

    override fun contentEquals(other: ReadBuffer): Boolean {
        checkNotFreed()
        return inner.contentEquals(other)
    }

    override fun mismatch(other: ReadBuffer): Int {
        checkNotFreed()
        return inner.mismatch(other)
    }

    override fun indexOf(byte: Byte): Int {
        checkNotFreed()
        return inner.indexOf(byte)
    }

    override fun indexOf(
        value: Short,
        aligned: Boolean,
    ): Int {
        checkNotFreed()
        return inner.indexOf(value, aligned)
    }

    override fun indexOf(
        value: Int,
        aligned: Boolean,
    ): Int {
        checkNotFreed()
        return inner.indexOf(value, aligned)
    }

    override fun indexOf(
        value: Long,
        aligned: Boolean,
    ): Int {
        checkNotFreed()
        return inner.indexOf(value, aligned)
    }

    // ========================================================================
    // WriteBuffer — fill (delegate to inner for optimized impls)
    // ========================================================================

    override fun fill(value: Byte): WriteBuffer {
        checkNotFreed()
        inner.fill(value)
        return this
    }
}
