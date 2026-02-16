package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ReadBuffer

/**
 * A decorator that automatically fills the underlying stream processor when data is needed.
 *
 * Eliminates the manual `ensureAvailable` loops found in protocol implementations:
 * ```kotlin
 * // Before (WebSocket):
 * while (streamProcessor.available() < 2) {
 *     readIntoProcessor(streamProcessor)
 * }
 * val frame = frameReader.readFrame()  // returns null if incomplete
 *
 * // After:
 * val frame = frameReader.readFrame()  // auto-fills, never returns null
 * ```
 *
 * The [refill] callback is invoked whenever a read or peek operation needs more data.
 * It must append data to the delegate via [append], or throw [EndOfStreamException]
 * if the data source is exhausted (e.g., socket closed).
 *
 * @param delegate The underlying stream processor to fill
 * @param refill Callback that reads more data into the stream. Must call [append] with new data,
 *   or throw [EndOfStreamException] if no more data is available.
 */
class AutoFillingSuspendingStreamProcessor(
    private val delegate: SuspendingStreamProcessor,
    private val refill: suspend (AutoFillingSuspendingStreamProcessor) -> Unit,
) : SuspendingStreamProcessor {
    override suspend fun append(chunk: ReadBuffer) = delegate.append(chunk)

    override fun available(): Int = delegate.available()

    private suspend fun ensureAvailable(minBytes: Int) {
        while (delegate.available() < minBytes) {
            refill(this)
        }
    }

    override suspend fun peekByte(offset: Int): Byte {
        ensureAvailable(offset + 1)
        return delegate.peekByte(offset)
    }

    override suspend fun peekShort(offset: Int): Short {
        ensureAvailable(offset + Short.SIZE_BYTES)
        return delegate.peekShort(offset)
    }

    override suspend fun peekInt(offset: Int): Int {
        ensureAvailable(offset + Int.SIZE_BYTES)
        return delegate.peekInt(offset)
    }

    override suspend fun peekLong(offset: Int): Long {
        ensureAvailable(offset + Long.SIZE_BYTES)
        return delegate.peekLong(offset)
    }

    override suspend fun peekMismatch(pattern: ReadBuffer): Int {
        ensureAvailable(pattern.remaining())
        return delegate.peekMismatch(pattern)
    }

    override suspend fun peekMatches(pattern: ReadBuffer): Boolean {
        ensureAvailable(pattern.remaining())
        return delegate.peekMatches(pattern)
    }

    override suspend fun readByte(): Byte {
        ensureAvailable(1)
        return delegate.readByte()
    }

    override suspend fun readUnsignedByte(): Int {
        ensureAvailable(1)
        return delegate.readUnsignedByte()
    }

    override suspend fun readShort(): Short {
        ensureAvailable(Short.SIZE_BYTES)
        return delegate.readShort()
    }

    override suspend fun readInt(): Int {
        ensureAvailable(Int.SIZE_BYTES)
        return delegate.readInt()
    }

    override suspend fun readLong(): Long {
        ensureAvailable(Long.SIZE_BYTES)
        return delegate.readLong()
    }

    override suspend fun readBuffer(size: Int): ReadBuffer {
        ensureAvailable(size)
        return delegate.readBuffer(size)
    }

    override suspend fun skip(count: Int) {
        ensureAvailable(count)
        delegate.skip(count)
    }

    override suspend fun finish() = delegate.finish()

    override fun release() = delegate.release()
}
