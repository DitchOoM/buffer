package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory [ByteSource] over a channel of buffers. Buffers are delivered as-is
 * (ownership transfer, zero-copy) — the same contract a real transport read path has.
 */
internal class ChannelByteSource(
    private val inbound: Channel<ReadBuffer>,
) : ByteSource {
    override val isOpen: Boolean get() = !inbound.isClosedForReceive

    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(5.seconds)

    override suspend fun read(deadline: Duration): ReadResult {
        suspend fun receive(): ReadResult {
            val result = inbound.receiveCatching()
            val buffer = result.getOrNull()
            return when {
                buffer != null -> ReadResult.Data(buffer)
                result.isClosed -> ReadResult.End
                else -> throw result.exceptionOrNull() ?: IllegalStateException("receive failed")
            }
        }
        return if (deadline.isInfinite()) receive() else withTimeout(deadline) { receive() }
    }
}

/** In-memory [ByteSink] over a channel of buffers. [close] closes the channel (a FIN). */
internal class ChannelByteSink(
    private val outbound: Channel<ReadBuffer>,
) : ByteSink {
    override val isOpen: Boolean get() = !outbound.isClosedForSend

    override val writePolicy: WritePolicy = WritePolicy.Bounded(5.seconds)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val size = buffer.remaining()
        outbound.send(buffer)
        return BytesWritten(size)
    }

    override suspend fun close() {
        outbound.close()
    }
}

/** In-memory duplex [ByteStream] composing a [ChannelByteSource] and [ChannelByteSink]. */
internal class MemoryByteStream(
    inbound: Channel<ReadBuffer>,
    outbound: Channel<ReadBuffer>,
) : ByteStream {
    private val source = ChannelByteSource(inbound)
    private val sink = ChannelByteSink(outbound)

    override val isOpen: Boolean get() = source.isOpen || sink.isOpen

    override val readPolicy: ReadPolicy get() = source.readPolicy

    override val writePolicy: WritePolicy get() = sink.writePolicy

    override suspend fun read(deadline: Duration): ReadResult = source.read(deadline)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = sink.write(buffer, deadline)

    override suspend fun close() {
        sink.close()
    }
}

/** A connected pair of in-memory byte streams: bytes written to one are read from the other. */
internal fun memoryByteStreamPair(): Pair<ByteStream, ByteStream> {
    val aToB = Channel<ReadBuffer>(Channel.UNLIMITED)
    val bToA = Channel<ReadBuffer>(Channel.UNLIMITED)
    return MemoryByteStream(bToA, aToB) to MemoryByteStream(aToB, bToA)
}

/** In-memory [ByteStreamMux] for testing raw accept + peek-then-classify flows. */
internal class MemoryByteStreamMux : ByteStreamMux {
    private val bidiQueue = Channel<ByteStream>(Channel.UNLIMITED)
    private val uniQueue = Channel<ByteSource>(Channel.UNLIMITED)

    override suspend fun openBidirectional(): ByteStream {
        val (local, remote) = memoryByteStreamPair()
        bidiQueue.send(remote)
        return local
    }

    override suspend fun openUnidirectional(): ByteSink {
        val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
        uniQueue.send(ChannelByteSource(channel))
        return ChannelByteSink(channel)
    }

    override suspend fun acceptBidirectional(): ByteStream = bidiQueue.receive()

    override suspend fun acceptUnidirectional(): ByteSource = uniQueue.receive()
}
