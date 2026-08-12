package com.ditchoom.buffer

/**
 * A [WriteBuffer] that discards bytes and counts them. Backs the derived default of
 * [TextEncoder.size]: running an author's `encodeInto` against this sink yields exactly the
 * bytes their real write produces — size and write cannot disagree because they ARE the same
 * code path, minus the stores.
 *
 * Effectively unbounded ([limit] is `Int.MAX_VALUE`); absolute writes ([set]) do not move the
 * counter, matching real-buffer semantics where absolute writes do not advance position.
 */
internal class CountingWriteBuffer : WriteBuffer {
    private var count = 0

    override val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

    override fun position(): Int = count

    override fun position(newPosition: Int) {
        count = newPosition
    }

    override fun limit(): Int = Int.MAX_VALUE

    override fun setLimit(limit: Int) = Unit

    override fun resetForWrite() {
        count = 0
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        count++
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer = this

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        count += length
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        // Counting a platform-divergent write is undefined; custom encoders should emit through
        // writeByte/writeBytes/writeText. UTF-8 has a defined common contract, so count that.
        when (charset) {
            Charset.UTF8 -> count += Utf8TextEncoder.sizeSubstituting(text)
            else -> throw UnsupportedOperationException(
                "CountingWriteBuffer cannot size charset $charset; emit via writeBytes or writeText",
            )
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val remaining = buffer.remaining()
        count += remaining
        buffer.position(buffer.position() + remaining)
    }
}
