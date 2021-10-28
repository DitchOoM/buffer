package com.ditchoom.buffer

/**
 * Provides a single ReadBuffer interface that delegates to multiple buffers.
 * While reading from a buffer sometimes you might need more data to complete the decoding operation. This class will
 * handle reading from multiple fragmented buffers in memory and provide a simple read api.
 */
@ExperimentalUnsignedTypes
class FragmentedReadBuffer(
    private val first: ReadBuffer,
    private val second: ReadBuffer
) : ReadBuffer {
    private val firstInitialLimit = first.limit()
    private val secondInitialLimit = second.limit()
    private var currentPosition = 0u
    private var currentLimit = firstInitialLimit + secondInitialLimit

    override fun setLimit(limit: Int) {
        if (limit.toUInt() <= firstInitialLimit + secondInitialLimit) {
            currentLimit = limit.toUInt()
        }
    }

    override fun limit() = firstInitialLimit + secondInitialLimit

    override fun position() = currentPosition

    override fun position(newPosition: Int) {
        currentPosition = newPosition.toUInt()
    }

    override fun resetForRead() {
        currentPosition = 0u
    }

    override fun readByte(): Byte {
        return if (currentPosition++ < firstInitialLimit) {
            first.readByte()
        } else {
            second.readByte()
        }
    }

    private fun <T> readSizeIntoBuffer(size: UInt, block: (ReadBuffer) -> T): T {
        val buffer = if (currentPosition < firstInitialLimit && currentPosition + size <= firstInitialLimit) {
            block(first)
        } else if (currentPosition < firstInitialLimit && currentPosition + size > firstInitialLimit) {
            val firstChunkSize = firstInitialLimit - currentPosition
            val secondChunkSize = size - firstChunkSize
            val secondBufferLimit = second.limit().toInt()
            second.setLimit((second.position() + secondChunkSize).toInt())
            val buffer = allocateNewBuffer(size)
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
        val first = first.slice()
        val second = second.slice()
        val buffer = allocateNewBuffer(first.limit() + second.limit())
        buffer.write(first)
        buffer.write(second)
        buffer.resetForRead()
        return buffer
    }

    override fun readByteArray(size: UInt): ByteArray {
        return readSizeIntoBuffer(size) { it.readByteArray(size) }
    }

    override fun readUnsignedByte(): UByte {
        return if (currentPosition++ < firstInitialLimit) {
            first.readUnsignedByte()
        } else {
            second.readUnsignedByte()
        }
    }

    override fun readUnsignedShort() = readSizeIntoBuffer(UShort.SIZE_BYTES.toUInt()) { it.readUnsignedShort() }

    override fun readUnsignedInt() = readSizeIntoBuffer(UInt.SIZE_BYTES.toUInt()) { it.readUnsignedInt() }


    override fun readLong() = readSizeIntoBuffer(ULong.SIZE_BYTES.toUInt()) { it.readLong() }

    override fun readUtf8(bytes: UInt) = readSizeIntoBuffer(bytes) { it.readUtf8(bytes) }
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
}

@ExperimentalUnsignedTypes
fun List<ReadBuffer>.toComposableBuffer(): ReadBuffer {
    return when (size) {
        1 -> {
            first()
        }
        else -> {
            FragmentedReadBuffer(
                first(),
                subList(1, size).toComposableBuffer()
            )
        }
    }
}