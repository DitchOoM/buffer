package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The on-wire bytes of a [Command] via the shared codec encode helper. */
private fun Command.wireBytes(): ByteArray {
    val buffer = CommandCodec.encodeToPlatformBuffer(this)
    return try {
        buffer.remainingBytes()
    } finally {
        buffer.freeNativeMemory()
    }
}

class FrameStreamingTest {
    private val messages =
        listOf(
            Command.Ping(ts = 0x1122334455667788L),
            Command.Echo(msg = "hi"),
            Command.Ping(ts = 1L),
            Command.Echo(msg = "hello world"),
        )

    @Test
    fun decodeFrames_overStaticChannel_decodesEveryFrameInOrder() =
        runTest {
            val stream = messages.fold(ByteArray(0)) { acc, m -> acc + m.wireBytes() }
            val decoded = ByteReadChannel(stream).decodeFrames(CommandCodec).toList()
            assertEquals(messages, decoded)
        }

    @Test
    fun encodeFrame_writesExactlyTheCodecBytes() =
        runTest {
            val message = Command.Echo(msg = "on the wire")
            val channel = ByteChannel(autoFlush = true)
            channel.encodeFrame(CommandCodec, message)
            channel.flushAndClose()

            assertContentEquals(message.wireBytes(), channel.readRemainingBuffer().remainingBytes())
        }

    @Test
    fun encodeFrame_then_decodeFrames_roundTripThroughLiveChannel() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val received = async { channel.decodeFrames(CommandCodec).toList() }
            messages.forEach { channel.encodeFrame(CommandCodec, it) }
            channel.flushAndClose()
            assertEquals(messages, received.await())
        }

    @Test
    fun decodeFrames_reassemblesFrameDeliveredInFragments() =
        runTest {
            val message = Command.Echo(msg = "fragmented across reads")
            val bytes = message.wireBytes()
            val channel = ByteChannel(autoFlush = true)
            val received = async { channel.decodeFrames(CommandCodec).toList() }

            // Deliver the frame in three flushed fragments so the length prefix and body each
            // straddle a read boundary.
            channel.writeByteArray(bytes.copyOfRange(0, 1))
            channel.flush()
            channel.writeByteArray(bytes.copyOfRange(1, 3))
            channel.flush()
            channel.writeByteArray(bytes.copyOfRange(3, bytes.size))
            channel.flushAndClose()

            assertEquals(listOf(message), received.await())
        }
}
