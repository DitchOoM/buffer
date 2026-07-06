package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.bufferEquals
import com.ditchoom.buffer.bufferHashCode

/**
 * Slice of a pooled buffer that tracks parent lifetime via reference counting.
 * When released, decrements the parent's refCount. The parent buffer is returned
 * to the pool only when all slices and the original chunk reference are released.
 *
 * Implements [PlatformBuffer] (writable) by delegating to [inner], which is the
 * concrete slice produced by the underlying buffer's `slice(byteOrder)`. Because
 * `PlatformBuffer.slice()` is contractually a `PlatformBuffer`, every slice
 * returned through a PooledBuffer is writable; writes propagate to the parent
 * buffer's underlying memory.
 *
 * ## Use-after-release safety
 *
 * A [TrackedSlice] aliases the pooled chunk's backing storage. Once the slice is
 * released **and** the parent's refcount reaches zero, that storage returns to the
 * pool's freelist and may be handed to the next acquirer. A retained reference to
 * a released slice would then silently read/write memory it no longer owns.
 *
 * To make that hazard fail fast instead of corrupting silently, every data-path
 * operation is explicitly overridden to call [checkNotReleased] before delegating
 * to [inner]. `PlatformBuffer by inner` delegation would otherwise forward each
 * call straight to the raw slice with no liveness check — including the methods
 * that carry default implementations, since Kotlin interface delegation shadows
 * defaults. The overrides are therefore exhaustive across the read, write,
 * absolute-index, bulk, search, fill and slice surfaces. The check is a single
 * boolean field read (no locking); [ReadBuffer.unwrapFully] and the
 * `nativeMemoryAccess` / `managedMemoryAccess` extensions resolve through the same
 * guard, so interop bridges (`toNativeData`, `toByteArray`, …) fail fast too.
 *
 * Pure positional getters ([position], [limit], [byteOrder], [capacity],
 * [remaining]) are intentionally left to delegation: they touch no backing bytes,
 * so reading them on a released slice cannot corrupt memory.
 */
internal class TrackedSlice(
    internal val inner: PlatformBuffer,
    private val parent: PooledBuffer,
) : PlatformBuffer by inner,
    PoolReleasable {
    private var released = false

    /**
     * Fails fast if this slice has been released back to the pool. Mirrors
     * [PooledBuffer]'s `checkNotFreed` use-after-free contract (same exception
     * type and intent). Internal so [ReadBuffer.unwrapFully] can gate wrapper
     * resolution on it.
     */
    internal fun checkNotReleased() {
        if (released) throw IllegalStateException("Buffer slice has been released back to the pool")
    }

    override fun releaseToPool() {
        if (!released) {
            released = true
            parent.releaseRef()
        }
    }

    // PlatformBuffer-by-delegation would resolve freeNativeMemory() to inner.slice()'s
    // freeNativeMemory, which is detached from the parent's refcount and would never
    // return the underlying pooled buffer to the pool. Route through releaseToPool()
    // instead so consumer-facing `freeNativeMemory()` decrements the parent refcount.
    override fun freeNativeMemory() {
        releaseToPool()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer {
        checkNotReleased()
        parent.addRef()
        return TrackedSlice(inner.slice(byteOrder), parent)
    }

    @Suppress("DEPRECATION")
    override fun unwrap(): PlatformBuffer {
        checkNotReleased()
        return inner.unwrap()
    }

    // ========================================================================
    // PositionBuffer — cursor mutation
    // ========================================================================

    override fun setLimit(limit: Int) {
        checkNotReleased()
        inner.setLimit(limit)
    }

    override fun position(newPosition: Int) {
        checkNotReleased()
        inner.position(newPosition)
    }

    // ========================================================================
    // ReadBuffer — relative reads
    // ========================================================================

    override fun resetForRead() {
        checkNotReleased()
        inner.resetForRead()
    }

    override fun readByte(): Byte {
        checkNotReleased()
        return inner.readByte()
    }

    override fun readUnsignedByte(): UByte {
        checkNotReleased()
        return inner.readUnsignedByte()
    }

    override fun readUByte(): UByte {
        checkNotReleased()
        return inner.readUByte()
    }

    override fun readShort(): Short {
        checkNotReleased()
        return inner.readShort()
    }

    override fun readUnsignedShort(): UShort {
        checkNotReleased()
        return inner.readUnsignedShort()
    }

    override fun readUShort(): UShort {
        checkNotReleased()
        return inner.readUShort()
    }

    override fun readInt(): Int {
        checkNotReleased()
        return inner.readInt()
    }

    override fun readUnsignedInt(): UInt {
        checkNotReleased()
        return inner.readUnsignedInt()
    }

    override fun readUInt(): UInt {
        checkNotReleased()
        return inner.readUInt()
    }

    override fun readLong(): Long {
        checkNotReleased()
        return inner.readLong()
    }

    override fun readUnsignedLong(): ULong {
        checkNotReleased()
        return inner.readUnsignedLong()
    }

    override fun readULong(): ULong {
        checkNotReleased()
        return inner.readULong()
    }

    override fun readFloat(): Float {
        checkNotReleased()
        return inner.readFloat()
    }

    override fun readDouble(): Double {
        checkNotReleased()
        return inner.readDouble()
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        checkNotReleased()
        return inner.readString(length, charset)
    }

    override fun readLine(): CharSequence {
        checkNotReleased()
        return inner.readLine()
    }

    override fun readNumberWithByteSize(numberOfBytes: Int): Long {
        checkNotReleased()
        return inner.readNumberWithByteSize(numberOfBytes)
    }

    override fun getNumberWithStartIndexAndByteSize(
        startIndex: Int,
        numberOfBytes: Int,
    ): Long {
        checkNotReleased()
        return inner.getNumberWithStartIndexAndByteSize(startIndex, numberOfBytes)
    }

    // ========================================================================
    // ReadBuffer — zero-copy views and byte-array reads
    // ========================================================================

    override fun readBytes(size: Int): ReadBuffer {
        checkNotReleased()
        return inner.readBytes(size)
    }

    override fun readByteArray(size: Int): ByteArray {
        checkNotReleased()
        return inner.readByteArray(size)
    }

    override fun copyToByteArray(size: Int): ByteArray {
        checkNotReleased()
        return inner.copyToByteArray(size)
    }

    override fun readInto(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readInto(dst, offset, length)
    }

    // ========================================================================
    // ReadBuffer — absolute reads
    // ========================================================================

    override fun get(index: Int): Byte {
        checkNotReleased()
        return inner.get(index)
    }

    override fun getUnchecked(index: Int): Byte {
        checkNotReleased()
        return inner.getUnchecked(index)
    }

    override fun getUnsignedByte(index: Int): UByte {
        checkNotReleased()
        return inner.getUnsignedByte(index)
    }

    override fun getShort(index: Int): Short {
        checkNotReleased()
        return inner.getShort(index)
    }

    override fun getUnsignedShort(index: Int): UShort {
        checkNotReleased()
        return inner.getUnsignedShort(index)
    }

    override fun getInt(index: Int): Int {
        checkNotReleased()
        return inner.getInt(index)
    }

    override fun getUnsignedInt(index: Int): UInt {
        checkNotReleased()
        return inner.getUnsignedInt(index)
    }

    override fun getLong(index: Int): Long {
        checkNotReleased()
        return inner.getLong(index)
    }

    override fun getLongUnchecked(index: Int): Long {
        checkNotReleased()
        return inner.getLongUnchecked(index)
    }

    override fun getUnsignedLong(index: Int): ULong {
        checkNotReleased()
        return inner.getUnsignedLong(index)
    }

    override fun getFloat(index: Int): Float {
        checkNotReleased()
        return inner.getFloat(index)
    }

    override fun getDouble(index: Int): Double {
        checkNotReleased()
        return inner.getDouble(index)
    }

    // ========================================================================
    // ReadBuffer — bulk primitive reads
    // ========================================================================

    override fun readShorts(count: Int): ShortArray {
        checkNotReleased()
        return inner.readShorts(count)
    }

    override fun readInts(count: Int): IntArray {
        checkNotReleased()
        return inner.readInts(count)
    }

    override fun readLongs(count: Int): LongArray {
        checkNotReleased()
        return inner.readLongs(count)
    }

    override fun readFloats(count: Int): FloatArray {
        checkNotReleased()
        return inner.readFloats(count)
    }

    override fun readDoubles(count: Int): DoubleArray {
        checkNotReleased()
        return inner.readDoubles(count)
    }

    override fun readShorts(
        dest: ShortArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readShorts(dest, offset, length)
    }

    override fun readInts(
        dest: IntArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readInts(dest, offset, length)
    }

    override fun readLongs(
        dest: LongArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readLongs(dest, offset, length)
    }

    override fun readFloats(
        dest: FloatArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readFloats(dest, offset, length)
    }

    override fun readDoubles(
        dest: DoubleArray,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.readDoubles(dest, offset, length)
    }

    // ========================================================================
    // ReadBuffer — search & comparison
    // ========================================================================

    override fun contentEquals(other: ReadBuffer): Boolean {
        checkNotReleased()
        return inner.contentEquals(other)
    }

    override fun mismatch(other: ReadBuffer): Int {
        checkNotReleased()
        return inner.mismatch(other)
    }

    override fun hashRange(
        offset: Int,
        length: Int,
    ): Long {
        checkNotReleased()
        return inner.hashRange(offset, length)
    }

    override fun indexOf(needle: ReadBuffer): Int {
        checkNotReleased()
        return inner.indexOf(needle)
    }

    override fun indexOf(byte: Byte): Int {
        checkNotReleased()
        return inner.indexOf(byte)
    }

    override fun indexOf(
        value: Short,
        aligned: Boolean,
    ): Int {
        checkNotReleased()
        return inner.indexOf(value, aligned)
    }

    override fun indexOf(
        value: Int,
        aligned: Boolean,
    ): Int {
        checkNotReleased()
        return inner.indexOf(value, aligned)
    }

    override fun indexOf(
        value: Long,
        aligned: Boolean,
    ): Int {
        checkNotReleased()
        return inner.indexOf(value, aligned)
    }

    override fun indexOf(
        text: CharSequence,
        charset: Charset,
    ): Int {
        checkNotReleased()
        return inner.indexOf(text, charset)
    }

    // ========================================================================
    // ReadBuffer — hex / base64 transforms
    // ========================================================================

    override fun encodeHexInto(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
        upperCase: Boolean,
    ) {
        checkNotReleased()
        inner.encodeHexInto(dest, offset, length, upperCase)
    }

    override fun decodeHexInto(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.decodeHexInto(dest, offset, length)
    }

    override fun encodeBase64Into(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
        urlSafe: Boolean,
        padded: Boolean,
    ) {
        checkNotReleased()
        inner.encodeBase64Into(dest, offset, length, urlSafe, padded)
    }

    override fun decodeBase64Into(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
    ) {
        checkNotReleased()
        inner.decodeBase64Into(dest, offset, length)
    }

    // ========================================================================
    // WriteBuffer — relative writes
    // ========================================================================

    override fun resetForWrite() {
        checkNotReleased()
        inner.resetForWrite()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkNotReleased()
        inner.writeByte(byte)
        return this
    }

    override fun writeUByte(uByte: UByte): WriteBuffer {
        checkNotReleased()
        inner.writeUByte(uByte)
        return this
    }

    override fun writeBytes(bytes: ByteArray): WriteBuffer {
        checkNotReleased()
        inner.writeBytes(bytes)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkNotReleased()
        inner.writeShort(short)
        return this
    }

    override fun writeUShort(uShort: UShort): WriteBuffer {
        checkNotReleased()
        inner.writeUShort(uShort)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkNotReleased()
        inner.writeInt(int)
        return this
    }

    override fun writeUInt(uInt: UInt): WriteBuffer {
        checkNotReleased()
        inner.writeUInt(uInt)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkNotReleased()
        inner.writeLong(long)
        return this
    }

    override fun writeULong(uLong: ULong): WriteBuffer {
        checkNotReleased()
        inner.writeULong(uLong)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        checkNotReleased()
        inner.writeFloat(float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        checkNotReleased()
        inner.writeDouble(double)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeString(text, charset)
        return this
    }

    override fun writeNumberOfByteSize(
        number: Long,
        byteSize: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeNumberOfByteSize(number, byteSize)
        return this
    }

    override fun setIndexNumberAndByteSize(
        index: Int,
        number: Long,
        byteSize: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.setIndexNumberAndByteSize(index, number, byteSize)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        checkNotReleased()
        inner.write(buffer)
    }

    // ========================================================================
    // WriteBuffer — absolute writes
    // ========================================================================

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, short)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, int)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, long)
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, float)
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, double)
        return this
    }

    override fun set(
        index: Int,
        uByte: UByte,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, uByte)
        return this
    }

    override fun set(
        index: Int,
        uShort: UShort,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, uShort)
        return this
    }

    override fun set(
        index: Int,
        uInt: UInt,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, uInt)
        return this
    }

    override fun set(
        index: Int,
        uLong: ULong,
    ): WriteBuffer {
        checkNotReleased()
        inner.set(index, uLong)
        return this
    }

    // ========================================================================
    // WriteBuffer — bulk primitive writes
    // ========================================================================

    override fun writeShorts(shorts: ShortArray): WriteBuffer {
        checkNotReleased()
        inner.writeShorts(shorts)
        return this
    }

    override fun writeInts(ints: IntArray): WriteBuffer {
        checkNotReleased()
        inner.writeInts(ints)
        return this
    }

    override fun writeLongs(longs: LongArray): WriteBuffer {
        checkNotReleased()
        inner.writeLongs(longs)
        return this
    }

    override fun writeFloats(floats: FloatArray): WriteBuffer {
        checkNotReleased()
        inner.writeFloats(floats)
        return this
    }

    override fun writeDoubles(doubles: DoubleArray): WriteBuffer {
        checkNotReleased()
        inner.writeDoubles(doubles)
        return this
    }

    override fun writeShorts(
        shorts: ShortArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeShorts(shorts, offset, length)
        return this
    }

    override fun writeInts(
        ints: IntArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeInts(ints, offset, length)
        return this
    }

    override fun writeLongs(
        longs: LongArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeLongs(longs, offset, length)
        return this
    }

    override fun writeFloats(
        floats: FloatArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeFloats(floats, offset, length)
        return this
    }

    override fun writeDoubles(
        doubles: DoubleArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeDoubles(doubles, offset, length)
        return this
    }

    // ========================================================================
    // ReadWriteBuffer — fill & masking
    // ========================================================================

    override fun fill(value: Byte): WriteBuffer {
        checkNotReleased()
        inner.fill(value)
        return this
    }

    override fun fill(value: Short): WriteBuffer {
        checkNotReleased()
        inner.fill(value)
        return this
    }

    override fun fill(value: Int): WriteBuffer {
        checkNotReleased()
        inner.fill(value)
        return this
    }

    override fun fill(value: Long): WriteBuffer {
        checkNotReleased()
        inner.fill(value)
        return this
    }

    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        checkNotReleased()
        inner.xorMask(mask, maskOffset)
    }

    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        checkNotReleased()
        inner.xorMaskCopy(source, mask, maskOffset)
    }

    // ========================================================================
    // Deprecated data-path overloads — still callable, still alias storage, so
    // they must fail fast too. Routed to the non-deprecated inner primitives.
    // ========================================================================

    @Deprecated("Use readString instead", ReplaceWith("readString(bytes.toInt(), Charset.UTF8)"))
    override fun readUtf8(bytes: UInt): CharSequence {
        checkNotReleased()
        return inner.readString(bytes.toInt(), Charset.UTF8)
    }

    @Deprecated("Use readString instead", ReplaceWith("readString(bytes, Charset.UTF8)"))
    override fun readUtf8(bytes: Int): CharSequence {
        checkNotReleased()
        return inner.readString(bytes, Charset.UTF8)
    }

    @Deprecated("Use readLine instead", ReplaceWith("readLine()"))
    override fun readUtf8Line(): CharSequence {
        checkNotReleased()
        return inner.readLine()
    }

    @Deprecated("Use writeString instead", ReplaceWith("writeString(text, Charset.UTF8)"))
    override fun writeUtf8(text: CharSequence): WriteBuffer {
        checkNotReleased()
        inner.writeString(text, Charset.UTF8)
        return this
    }

    @Deprecated("Use writeByte for explicitness.", ReplaceWith("writeByte(byte)"))
    override fun write(byte: Byte): WriteBuffer {
        checkNotReleased()
        inner.writeByte(byte)
        return this
    }

    @Deprecated("Use writeBytes for explicitness.", ReplaceWith("writeBytes(bytes)"))
    override fun write(bytes: ByteArray): WriteBuffer {
        checkNotReleased()
        inner.writeBytes(bytes)
        return this
    }

    @Deprecated("Use writeBytes for explicitness.", ReplaceWith("writeBytes(bytes, offset, length)"))
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkNotReleased()
        inner.writeBytes(bytes, offset, length)
        return this
    }

    @Deprecated("Use writeUByte for explicitness.", ReplaceWith("writeUByte(uByte)"))
    override fun write(uByte: UByte): WriteBuffer {
        checkNotReleased()
        inner.writeUByte(uByte)
        return this
    }

    @Deprecated("Use writeShort for explicitness.", ReplaceWith("writeShort(short)"))
    override fun write(short: Short): WriteBuffer {
        checkNotReleased()
        inner.writeShort(short)
        return this
    }

    @Deprecated("Use writeUShort for explicitness.", ReplaceWith("writeUShort(uShort)"))
    override fun write(uShort: UShort): WriteBuffer {
        checkNotReleased()
        inner.writeUShort(uShort)
        return this
    }

    @Deprecated("Use writeInt for explicitness.", ReplaceWith("writeInt(int)"))
    override fun write(int: Int): WriteBuffer {
        checkNotReleased()
        inner.writeInt(int)
        return this
    }

    @Deprecated("Use writeUInt for explicitness.", ReplaceWith("writeUInt(uInt)"))
    override fun write(uInt: UInt): WriteBuffer {
        checkNotReleased()
        inner.writeUInt(uInt)
        return this
    }

    @Deprecated("Use writeLong for explicitness.", ReplaceWith("writeLong(long)"))
    override fun write(long: Long): WriteBuffer {
        checkNotReleased()
        inner.writeLong(long)
        return this
    }

    @Deprecated("Use writeULong for explicitness.", ReplaceWith("writeULong(uLong)"))
    override fun write(uLong: ULong): WriteBuffer {
        checkNotReleased()
        inner.writeULong(uLong)
        return this
    }

    @Deprecated("Use writeFloat for explicitness.", ReplaceWith("writeFloat(float)"))
    override fun write(float: Float): WriteBuffer {
        checkNotReleased()
        inner.writeFloat(float)
        return this
    }

    @Deprecated("Use writeDouble for explicitness.", ReplaceWith("writeDouble(double)"))
    override fun write(double: Double): WriteBuffer {
        checkNotReleased()
        inner.writeDouble(double)
        return this
    }

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
