package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/** Encodes a [Command] to its on-wire bytes via the shared codec encode helper. */
private fun Command.encodedBytes(): ByteArray {
    val buffer = CommandCodec.encodeToPlatformBuffer(this)
    return try {
        buffer.copyToByteArray(buffer.remaining())
    } finally {
        buffer.freeNativeMemory()
    }
}

class TypedTest {
    private val mixed =
        listOf(
            Command.Ping(ts = 0x1122334455667788L),
            Command.Echo(msg = "hi"),
            Command.Ping(ts = 1L),
            Command.Echo(msg = "hello world"),
        )

    @Test
    fun typedConnectionRoundTripsMixedMessagesInOrder() =
        runTest {
            val (a, b) = memoryByteStreamPair()
            val sender = a.typed(CommandCodec)
            val receiver = b.typed(CommandCodec)

            val received = async { receiver.receive().take(mixed.size).toList() }
            mixed.forEach { sender.send(it) }

            assertEquals(mixed, received.await())
        }

    @Test
    fun typedSourceReassemblesFrameSplitAcrossSingleByteReads() =
        runTest {
            // Concatenate several frames, then deliver the whole run one byte per read so every
            // frame boundary and length prefix lands mid-chunk.
            val stream = mixed.fold(ByteArray(0)) { acc, m -> acc + m.encodedBytes() }
            val inbound = Channel<ReadBuffer>(Channel.UNLIMITED)
            for (byte in stream) {
                inbound.send(BufferFactory.Default.wrap(byteArrayOf(byte)))
            }
            inbound.close()

            val received = ChannelByteSource(inbound).typed(CommandCodec).receive().toList()

            assertEquals(mixed, received)
        }

    @Test
    fun typedSourceCompletesOnCleanEnd() =
        runTest {
            val inbound = Channel<ReadBuffer>(Channel.UNLIMITED)
            inbound.send(BufferFactory.Default.wrap(Command.Ping(ts = 7L).encodedBytes()))
            inbound.close()

            val received = ChannelByteSource(inbound).typed(CommandCodec).receive().toList()

            assertEquals(listOf(Command.Ping(ts = 7L)), received)
        }

    @Test
    fun typedSourceSurfacesResetAsException() =
        runTest {
            val resettingSource =
                object : ByteSource {
                    override val isOpen: Boolean = true
                    override val readPolicy: ReadPolicy = ReadPolicy.UntilClosed

                    override suspend fun read(deadline: Duration): ReadResult = ReadResult.Reset
                }

            assertFailsWith<ByteStreamResetException> {
                resettingSource.typed(CommandCodec).receive().toList()
            }
        }

    @Test
    fun typedSinkEncodesExactCodecBytesOntoTheWire() =
        runTest {
            val outbound = Channel<ReadBuffer>(Channel.UNLIMITED)
            val sender = ChannelByteSink(outbound).typed(CommandCodec)

            val message = Command.Echo(msg = "on the wire")
            sender.send(message)
            outbound.close()

            val onWire =
                outbound.toList().fold(ByteArray(0)) { acc, buf ->
                    acc + buf.copyToByteArray(buf.remaining())
                }
            assertEquals(message.encodedBytes().toList(), onWire.toList())
        }
}
