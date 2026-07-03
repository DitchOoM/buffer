package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.counting
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spike 3a/3b: non-consuming peek + the "no copy on classify" accounting guard.
 *
 * The zero-copy claims are pinned with [com.ditchoom.buffer.CountingBufferFactory] counts, not
 * by eyeballing: classification (peek a self-describing stream-type prefix, then hand the raw
 * stream on) must never allocate a payload-sized buffer. A future refactor that regresses into
 * copy-the-remainder fails these tests.
 */
class BufferedByteSourceTest {
    /** Decodes a QUIC variable-length integer (RFC 9000 §16) relative to the buffer position. */
    private fun decodeQuicVarInt(buffer: ReadBuffer): Long {
        val first = buffer.readByte().toInt() and 0xFF
        val length = 1 shl ((first ushr 6) and 0x3)
        var value = (first and 0x3F).toLong()
        repeat(length - 1) {
            value = (value shl 8) or (buffer.readByte().toLong() and 0xFF)
        }
        return value
    }

    private fun bufferOf(vararg bytes: Int): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        for (b in bytes) buffer.writeByte(b.toByte())
        buffer.resetForRead()
        return buffer
    }

    // Stream type 0x54 encodes as the 2-byte QUIC varint [0x40, 0x54].
    private val streamType = 0x54L

    private fun prefixedPayload(payloadSize: Int): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(2 + payloadSize)
        buffer.writeByte(0x40)
        buffer.writeByte(0x54)
        repeat(payloadSize) { buffer.writeByte((it % 251).toByte()) }
        buffer.resetForRead()
        return buffer
    }

    @Test
    fun peekThenReadRedeliversTheSameBytes() =
        runTest {
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            val original = prefixedPayload(payloadSize = 64)
            channel.send(original)
            channel.close()
            val source = BufferedByteSource(ChannelByteSource(channel))

            val peeked = source.peek(8)
            assertIs<ReadResult.Data>(peeked)
            assertEquals(8, peeked.buffer.remaining())
            assertEquals(streamType, decodeQuicVarInt(peeked.buffer))

            // The first real read still returns ALL the bytes, prefix included.
            val read = source.read()
            assertIs<ReadResult.Data>(read)
            assertEquals(2 + 64, read.buffer.remaining())
            assertEquals(streamType, decodeQuicVarInt(read.buffer))
            assertEquals(ReadResult.End, source.read())
        }

    @Test
    fun peekIsIdempotent() =
        runTest {
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            channel.send(prefixedPayload(payloadSize = 16))
            val source = BufferedByteSource(ChannelByteSource(channel))

            val first = source.peek(8)
            val second = source.peek(8)
            assertIs<ReadResult.Data>(first)
            assertIs<ReadResult.Data>(second)
            assertEquals(streamType, decodeQuicVarInt(first.buffer))
            assertEquals(streamType, decodeQuicVarInt(second.buffer))
        }

    @Test
    fun peekWithinOneChunkAllocatesNothing() =
        runTest {
            val counting = BufferFactory.Default.counting()
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            channel.send(prefixedPayload(payloadSize = 4096))
            val source = BufferedByteSource(ChannelByteSource(channel), counting)

            val peeked = source.peek(8)
            assertIs<ReadResult.Data>(peeked)
            assertEquals(streamType, decodeQuicVarInt(peeked.buffer))
            assertEquals(0L, counting.allocationCount, "single-chunk peek must be a zero-copy view")
        }

    @Test
    fun peekSpanningChunksAllocatesOnlyTheHeader() =
        runTest {
            val counting = BufferFactory.Default.counting()
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            // The varint prefix arrives split across two 1-byte chunks, then the payload.
            channel.send(bufferOf(0x40))
            channel.send(bufferOf(0x54))
            val payloadSize = 4096
            val payload = BufferFactory.Default.allocate(payloadSize)
            repeat(payloadSize) { payload.writeByte((it % 251).toByte()) }
            payload.resetForRead()
            channel.send(payload)
            channel.close()
            val source = BufferedByteSource(ChannelByteSource(channel), counting)

            val peeked = source.peek(2)
            assertIs<ReadResult.Data>(peeked)
            assertEquals(streamType, decodeQuicVarInt(peeked.buffer))
            assertEquals(1L, counting.allocationCount, "spanning peek stages exactly once")
            assertEquals(2, counting.largestAllocationSize, "staging is header-sized, never payload-sized")

            // Reads re-deliver every buffered chunk, in order, uncopied.
            assertEquals(0x40, (source.read() as ReadResult.Data).buffer.readByte().toInt())
            assertEquals(0x54, (source.read() as ReadResult.Data).buffer.readByte().toInt())
            val readPayload = source.read()
            assertIs<ReadResult.Data>(readPayload)
            assertSame(payload, readPayload.buffer, "payload chunk is ownership-transferred, not copied")
            assertEquals(1L, counting.allocationCount, "reads after peek allocate nothing")
            assertEquals(ReadResult.End, source.read())
        }

    @Test
    fun peekOnEmptyClosedStreamReturnsEnd() =
        runTest {
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            channel.close()
            val source = BufferedByteSource(ChannelByteSource(channel))
            assertEquals(ReadResult.End, source.peek(8))
            assertEquals(ReadResult.End, source.read())
        }

    @Test
    fun peekAtEofReturnsShortData() =
        runTest {
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            channel.send(bufferOf(0x00))
            channel.close()
            val source = BufferedByteSource(ChannelByteSource(channel))

            val peeked = source.peek(8)
            assertIs<ReadResult.Data>(peeked)
            assertEquals(1, peeked.buffer.remaining())

            val read = source.read()
            assertIs<ReadResult.Data>(read)
            assertEquals(1, read.buffer.remaining())
            assertEquals(ReadResult.End, source.read())
        }

    @Test
    fun readWithoutPeekPassesThrough() =
        runTest {
            val counting = BufferFactory.Default.counting()
            val channel = Channel<ReadBuffer>(Channel.UNLIMITED)
            val original = bufferOf(1, 2, 3)
            channel.send(original)
            val source = BufferedByteSource(ChannelByteSource(channel), counting)

            val read = source.read()
            assertIs<ReadResult.Data>(read)
            assertSame(original, read.buffer)
            assertEquals(0L, counting.allocationCount)
        }

    /**
     * Spike 3b end-to-end: accept a raw self-describing stream off a [ByteStreamMux], classify it
     * by peeking the stream-type varint, then consume it — with **zero** allocations attributable
     * to classification. This is the guard for the whole peek-then-wrap demux path.
     */
    @Test
    fun classifyingAnAcceptedStreamCopiesNothing() =
        runTest {
            val mux = MemoryByteStreamMux()
            val sender = mux.openUnidirectional()
            val payloadSize = 4096
            val original = prefixedPayload(payloadSize)
            sender.write(original)
            sender.close()

            val counting = BufferFactory.Default.counting()
            val accepted = BufferedByteSource(mux.acceptUnidirectional(), counting)

            // Classify: peek the (≤ 8 byte) stream-type prefix without consuming it.
            val peeked = accepted.peek(8)
            assertIs<ReadResult.Data>(peeked)
            assertEquals(streamType, decodeQuicVarInt(peeked.buffer))

            // Consume: the classified stream re-delivers the untouched original buffer.
            val read = accepted.read()
            assertIs<ReadResult.Data>(read)
            assertSame(original, read.buffer, "classified stream hands back the transport buffer itself")
            assertEquals(streamType, decodeQuicVarInt(read.buffer))
            assertEquals(payloadSize, read.buffer.remaining())
            var index = 0
            while (read.buffer.hasRemaining()) {
                assertEquals((index % 251).toByte(), read.buffer.readByte())
                index++
            }

            assertEquals(0L, counting.allocationCount, "classification must not allocate")
            assertEquals(0L, counting.wrapCount, "classification must not wrap")
            assertTrue(counting.allocatedBytes == 0L)
            assertEquals(ReadResult.End, accepted.read())
        }
}
