package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.TextPolicy
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.bufferEquals
import com.ditchoom.buffer.bufferHashCode
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
@OptIn(ExperimentalAtomicApi::class)
internal class PooledBuffer(
    internal val inner: PlatformBuffer,
    internal val pool: BufferPool,
) : PlatformBuffer by inner {
    /**
     * Whether this chunk's references may be taken and dropped from more than one thread.
     *
     * Atomic reference counting is **not free**: on x86_64 it measured ~33% slower on the
     * slice/read/release path (70.4M -> 47.5M ops/s, medians over three alternating rounds), and
     * neither a CAS retry loop nor a single fetch-and-add avoided that — the cost is the atomic
     * RMW itself, not the choice of primitive. On ARM64 (LSE) it was free. So the count is atomic
     * exactly when correctness requires it and plain otherwise, rather than everywhere or nowhere.
     *
     * `SingleThreaded` is [BufferPool]'s default and the single-consumer hot path; it keeps the
     * plain counter and is unchanged. `MultiThreaded` pools — the ones a buffer shared and
     * released across more than one thread or coroutine must come from — pay for the safety they
     * actually need.
     */
    private val shared = pool.threadingMode == ThreadingMode.MultiThreaded

    // Exactly one of these two is live, chosen by [shared]. Both are always allocated: `AtomicInt`
    // is a heap object on JVM/JS and PooledBuffer is constructed per `acquire()`, so making the
    // atomic conditional would trade a branch for an allocation on the hot path.
    private var plainRefCount = 1 // 1 for the chunk reference in StreamProcessor
    private val sharedRefCount = AtomicInt(1)

    private var plainFreed = false
    private val sharedFreed = AtomicInt(0)

    private fun checkNotFreed() {
        val isFreed = if (shared) sharedFreed.load() != 0 else plainFreed
        if (isFreed) throw IllegalStateException("Buffer has been freed and returned to pool")
    }

    internal fun addRef() {
        if (!shared) {
            plainRefCount++
            return
        }
        // Resurrection from zero is refused: it would hand out a slice onto storage another
        // acquirer already owns. Detected after the increment and undone, because reaching it at
        // all is a caller bug rather than a race to tolerate.
        val previous = sharedRefCount.fetchAndAdd(1)
        if (previous <= 0) {
            sharedRefCount.fetchAndAdd(-1)
            throw IllegalStateException("PooledBuffer.addRef() after the last reference was released")
        }
    }

    internal fun releaseRef() {
        val remaining =
            if (shared) {
                sharedRefCount.fetchAndAdd(-1) - 1
            } else {
                --plainRefCount
            }
        check(remaining >= 0) { "PooledBuffer.releaseRef() called more times than it was retained" }
        // Exactly one caller observes the 1 -> 0 transition, so the chunk is returned once.
        if (remaining == 0) pool.release(inner)
    }

    override fun freeNativeMemory() {
        val alreadyFreed =
            if (shared) {
                sharedFreed.exchange(1) != 0
            } else {
                plainFreed.also { plainFreed = true }
            }
        if (!alreadyFreed) releaseRef()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer {
        checkNotFreed()
        addRef()
        return TrackedSlice(inner.slice(byteOrder), this)
    }

    fun close() {
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

    override fun readInto(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkNotFreed()
        inner.readInto(dst, offset, length)
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

    override fun <D> readText(
        length: Int,
        policy: TextPolicy<*, D>,
    ): D {
        checkNotFreed()
        // No rewrap: read results never carry the buffer.
        return inner.readText(length, policy)
    }

    override fun <W> writeText(
        text: CharSequence,
        policy: TextPolicy<W, *>,
    ): W {
        checkNotFreed()
        // rewrap re-binds fluent results to this wrapper instead of leaking the inner buffer.
        return policy.rewrap(inner.writeText(text, policy), this)
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

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
