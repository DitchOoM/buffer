package com.ditchoom.buffer

/**
 * Provides a single ReadBuffer interface that delegates to multiple buffers.
 * While reading from a buffer sometimes you might need more data to complete the decoding operation. This class will
 * handle reading from multiple fragmented buffers in memory and provide a simple read api.
 */
class FragmentedReadBuffer(
    private val first: ReadBuffer,
    private val second: ReadBuffer,
) : ReadBuffer {
    private val firstInitialLimit = first.limit()
    private val secondInitialLimit = second.limit()
    private var currentPosition = 0
    private var currentLimit = firstInitialLimit + secondInitialLimit

    override val byteOrder: ByteOrder
        get() {
            throw IllegalStateException("Byte order is undefined for FragmentedReadBuffer")
        }

    override fun setLimit(limit: Int) {
        if (limit <= firstInitialLimit + secondInitialLimit) {
            currentLimit = limit
        }
    }

    override fun limit() = currentLimit

    override fun position() = currentPosition

    override fun position(newPosition: Int) {
        currentPosition = newPosition
    }

    override fun resetForRead() {
        currentPosition = 0
        first.resetForRead()
        second.resetForRead()
    }

    override fun readByte(): Byte =
        if (currentPosition++ < firstInitialLimit) {
            first.readByte()
        } else {
            second.readByte()
        }

    override fun get(index: Int): Byte =
        if (currentPosition < firstInitialLimit) {
            first.readByte()
        } else {
            second.readByte()
        }

    private fun <T> readSizeIntoBuffer(
        size: Int,
        block: (ReadBuffer) -> T,
    ): T {
        val buffer =
            if (currentPosition < firstInitialLimit && currentPosition + size <= firstInitialLimit) {
                block(first)
            } else if (currentPosition < firstInitialLimit && currentPosition + size > firstInitialLimit) {
                val firstChunkSize = firstInitialLimit - currentPosition
                val secondChunkSize = size - firstChunkSize
                val secondBufferLimit = second.limit()
                second.setLimit(second.position() + secondChunkSize)
                val buffer = PlatformBuffer.allocate(size)
                buffer.write(first)
                buffer.write(second)
                second.setLimit(secondBufferLimit)
                buffer.resetForRead()
                block(buffer)
            } else {
                block(second)
            }
        currentPosition += size
        return buffer
    }

    override fun slice(): ReadBuffer {
        if (first.position() == 0 && first.limit() == 0) {
            return second
        } else if (second.position() == 0 && second.limit() == 0) {
            return first
        }
        val first = first.slice()
        val second = second.slice()
        val buffer = PlatformBuffer.allocate(first.limit() + second.limit())
        buffer.write(first)
        buffer.write(second)
        buffer.resetForRead()
        return buffer
    }

    override fun readByteArray(size: Int): ByteArray = readSizeIntoBuffer(size) { it.readByteArray(size) }

    override fun readShort() = readSizeIntoBuffer(Short.SIZE_BYTES) { it.readShort() }

    override fun readInt() = readSizeIntoBuffer(Int.SIZE_BYTES) { it.readInt() }

    override fun readLong() = readSizeIntoBuffer(ULong.SIZE_BYTES) { it.readLong() }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String = readSizeIntoBuffer(length) { it.readString(length, charset) }

    override fun readUtf8Line(): CharSequence {
        if (currentPosition < firstInitialLimit) {
            val initialFirstPosition = first.position()
            val firstUtf8 = first.readUtf8Line()
            val bytesRead = first.position() - initialFirstPosition
            return if (firstUtf8.toString().utf8Length().toLong() == bytesRead.toLong()) {
                // read the entire string, check the second one
                currentPosition = firstInitialLimit
                val secondInitialPosition = second.position()
                val secondLine = second.readUtf8Line()
                currentPosition += second.position() - secondInitialPosition
                StringBuilder(firstUtf8).append(secondLine)
            } else {
                firstUtf8
            }
        } else {
            val secondInitialPosition = second.position()
            val line = second.readUtf8Line()
            currentPosition += second.position() - secondInitialPosition
            return line
        }
    }

    fun getBuffers(out: MutableList<PlatformBuffer>) {
        if (first is FragmentedReadBuffer) {
            first.getBuffers(out)
        } else if (first is PlatformBuffer) {
            out += first
        }
        if (second is FragmentedReadBuffer) {
            second.getBuffers(out)
        } else if (second is PlatformBuffer) {
            out += second
        }
    }

    fun toSingleBuffer(zone: AllocationZone = AllocationZone.Heap): PlatformBuffer {
        val firstLimit = firstInitialLimit
        val secondLimit = secondInitialLimit
        val initialPosition = position()
        val buffer = PlatformBuffer.allocate(firstLimit + secondLimit - initialPosition, zone)
        walk {
            buffer.write(it)
        }
        buffer.position(initialPosition)
        return buffer
    }

    fun walk(visitor: (ReadBuffer) -> Unit) {
        if (first is FragmentedReadBuffer) {
            first.walk(visitor)
        } else {
            visitor(first)
        }
        if (second is FragmentedReadBuffer) {
            second.walk(visitor)
        } else {
            visitor(second)
        }
    }
}

fun List<ReadBuffer>.toComposableBuffer(): ReadBuffer {
    if (isEmpty()) {
        return ReadBuffer.EMPTY_BUFFER
    }
    if (size == 1) {
        return first()
    }
    if (size == 2) {
        return FragmentedReadBuffer(first(), get(1))
    }
    val half = size / 2
    return FragmentedReadBuffer(
        subList(0, half).toComposableBuffer(),
        subList(half, size).toComposableBuffer(),
    )
}
