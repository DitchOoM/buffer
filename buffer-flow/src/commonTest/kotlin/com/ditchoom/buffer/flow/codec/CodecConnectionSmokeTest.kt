package com.ditchoom.buffer.flow.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/**
 * Stage H acceptance #4 (PHASE_9_RESET §"Stage H — Payload SAM via MQTT v5
 * PUBLISH"): pushes PUBLISH frames through `Connection<MqttPacket<TextPayload>>`
 * and pulls them out the other side via the codec-emitter API surface.
 *
 * Uses `TextPayload` rather than `JpegImage` because `JpegImage.data` decodes
 * to a `ByteArray` — fine for a worked-vector test, but it would muddy the
 * "no `ByteArray` allocations on the hot path" claim in this smoke test.
 * `TextPayloadCodec` decodes via `readString` and encodes via `writeString`,
 * which is zero-`ByteArray` on JVM/Apple/JS (Locked Decision row 16 calls
 * out the WASM/`nonJvm` `writeString` carve-out).
 */
class CodecConnectionSmokeTest {
    @Test
    fun publishRoundTripsThroughConnection() =
        runTest {
            val (clientByteStream, serverByteStream) = inMemoryByteStreamPair()
            val pool = BufferPool()
            try {
                val client =
                    clientByteStream.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val server =
                    serverByteStream.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val publish =
                    MqttPacket.Publish<TextPayload>(
                        header = MqttFixedHeader(0x32u),
                        // body = topic LP (2 + 7) + packetId (2) + payload "ping" (4) = 15
                        remainingLength = 15u,
                        topic = "hello/1",
                        packetId = PacketId(7u),
                        payload = TextPayload("ping"),
                    )
                client.send(publish)
                val received = server.receive().first()
                assertEquals(publish, received)
                client.close()
                server.close()
            } finally {
                pool.clear()
            }
        }

    @Test
    fun payloadFreeVariantsRoundTrip() =
        runTest {
            // <Nothing>-typed Connect / Disconnect flow through
            // Connection<MqttPacket<TextPayload>> via covariance.
            val (a, b) = inMemoryByteStreamPair()
            val pool = BufferPool()
            try {
                val sender =
                    a.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val receiver =
                    b.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val connect =
                    MqttPacket.Connect(
                        header = MqttFixedHeader(0x10u),
                        // body = keepalive (2) + clientId LP (2 + 4) = 8
                        remainingLength = 8u,
                        keepAliveSeconds = 60u,
                        clientId = "abcd",
                    )
                val disconnect = MqttPacket.Disconnect()
                sender.send(connect)
                sender.send(disconnect)
                val received = receiver.receive().take(2).toList()
                assertEquals(listOf<MqttPacket<TextPayload>>(connect, disconnect), received)
                sender.close()
                receiver.close()
            } finally {
                pool.clear()
            }
        }

    @Test
    fun peekFramingHandlesSplitWrites() =
        runTest {
            // Encode a Publish into a buffer, then drip-feed it byte-by-byte
            // into the receive byte-stream. peekFrameSize on the dispatcher
            // walks the var-int + LP topic + packetId; the receive loop
            // surfaces exactly one decoded message after the final byte.
            val (a, b) = inMemoryByteStreamPair()
            val pool = BufferPool()
            try {
                val receiver =
                    b.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val publish =
                    MqttPacket.Publish<TextPayload>(
                        header = MqttFixedHeader(0x32u),
                        // body = topic LP (2 + 1) + packetId (2) + payload "hi" (2) = 7
                        remainingLength = 7u,
                        topic = "x",
                        packetId = PacketId(1u),
                        payload = TextPayload("hi"),
                    )
                val encodeBuf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
                MqttPacketCodec(TextPayloadCodec).encode(
                    encodeBuf,
                    publish,
                    EncodeContext.Empty,
                )
                encodeBuf.resetForRead()

                while (encodeBuf.remaining() > 0) {
                    val one = BufferFactory.Default.allocate(1)
                    one.writeByte(encodeBuf.readByte())
                    one.resetForRead()
                    a.write(one)
                }

                val received = receiver.receive().first()
                assertEquals(publish, received)
                receiver.close()
            } finally {
                pool.clear()
            }
        }

    @Test
    fun aggregatorPathTopicKeyed() =
        runTest {
            // Slice 10d.5 seam at the Connection boundary: instead of using
            // the dispatcher's standard decode, the receive side could call
            // decodeAggregating and pick a payload codec by topic per call.
            // The bridge today wires Codec.decode (constructor-injected
            // payload codec) — this test confirms the topic-keyed shape
            // works at the edge by encoding/decoding via decodeAggregating
            // outside the bridge, sending the bytes through a ByteStream
            // wrapped as a codec connection, and asserting both halves
            // round-trip the same Publish.
            val (a, b) = inMemoryByteStreamPair()
            val pool = BufferPool()
            try {
                val sender =
                    a.asCodecConnection(
                        codec = MqttPacketCodec(TextPayloadCodec),
                        pool = pool,
                        scope = backgroundScope,
                    )
                val publish =
                    MqttPacket.Publish<TextPayload>(
                        header = MqttFixedHeader(0x32u),
                        remainingLength = 19u, // 2 + 7 (topic) + 2 (pid) + 8 (payload "byTopic!")
                        topic = "topic/A",
                        packetId = PacketId(99u),
                        payload = TextPayload("byTopic!"),
                    )
                sender.send(publish)

                // Pull bytes off the other side using the aggregator API
                // directly (no bridge codec) to demonstrate the shape works.
                // Aggregate frames as a ReadBuffer via raw ByteStream reads.
                val pool2 = BufferPool()
                val processor = StreamProcessor.create(pool2, ByteOrder.BIG_ENDIAN)
                try {
                    while (true) {
                        when (val r = b.read()) {
                            is ReadResult.Data -> processor.append(r.buffer)
                            else -> error("unexpected end")
                        }
                        val peek = MqttPacketCodec(TextPayloadCodec).peekFrameSize(processor)
                        if (peek is PeekResult.Complete) {
                            val decoded =
                                processor.readBufferScoped(peek.bytes) {
                                    MqttPacketCodec.decodeAggregating<TextPayload>(
                                        this,
                                        DecodeContext.Empty,
                                        onPublish = { partial ->
                                            // Topic-keyed selection at the call site.
                                            val codec =
                                                when (partial.topic) {
                                                    "topic/A" -> TextPayloadCodec
                                                    else -> error("unknown topic ${partial.topic}")
                                                }
                                            partial.complete(codec)
                                        },
                                    )
                                }
                            assertEquals(publish, decoded)
                            assertIs<MqttPacket.Publish<TextPayload>>(decoded)
                            break
                        }
                    }
                } finally {
                    processor.release()
                    pool2.clear()
                }
                sender.close()
            } finally {
                pool.clear()
            }
        }

    // ----------------------------------------------------------------
    // In-memory ByteStream pair for the smoke test.
    // ----------------------------------------------------------------

    private fun inMemoryByteStreamPair(): Pair<ByteStream, ByteStream> {
        val aToB = Channel<ReadBuffer>(Channel.UNLIMITED)
        val bToA = Channel<ReadBuffer>(Channel.UNLIMITED)
        return MemoryByteStream(write = aToB, read = bToA) to MemoryByteStream(write = bToA, read = aToB)
    }

    private class MemoryByteStream(
        private val write: Channel<ReadBuffer>,
        private val read: Channel<ReadBuffer>,
    ) : ByteStream {
        private var open = true

        override val isOpen: Boolean get() = open

        override suspend fun read(timeout: Duration): ReadResult =
            try {
                ReadResult.Data(read.receive())
            } catch (_: ClosedReceiveChannelException) {
                ReadResult.End
            }

        override suspend fun write(
            buffer: ReadBuffer,
            timeout: Duration,
        ): BytesWritten {
            val count = buffer.remaining()
            write.send(buffer)
            return BytesWritten(count)
        }

        override suspend fun close() {
            open = false
            write.close()
            read.close()
        }
    }
}
