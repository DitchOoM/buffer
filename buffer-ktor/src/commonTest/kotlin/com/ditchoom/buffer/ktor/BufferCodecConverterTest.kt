package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStrings
import com.ditchoom.buffer.managed
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.reflect.typeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Wraps a [BufferFactory], counting [allocate] calls and every [PlatformBuffer.freeNativeMemory]
 * call on the buffers it hands out. Shared across converter/encoding leak tests in this package.
 */
internal class AllocFreeCountingFactory(
    private val delegate: BufferFactory,
) : BufferFactory by delegate {
    var allocations = 0
        private set
    var frees = 0
        private set

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        allocations++
        return FreeCountingBuffer(delegate.allocate(size, byteOrder)) { frees++ }
    }
}

internal class FreeCountingBuffer(
    private val delegate: PlatformBuffer,
    private val onFree: () -> Unit,
) : PlatformBuffer by delegate {
    override fun freeNativeMemory() {
        onFree()
        delegate.freeNativeMemory()
    }
}

/** Delegates encode/wireSize/peekFrameSize to [delegate] but always throws from [decode]. */
private class ThrowingDecodeCodec<T>(
    private val delegate: Codec<T>,
) : Codec<T> by delegate {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T = throw IllegalStateException("boom")
}

private fun Command.encodedBytes(): ByteArray {
    val buffer = CommandCodec.encodeToPlatformBuffer(this, BufferFactory.Default)
    val bytes = buffer.copyToByteArray(buffer.remaining())
    buffer.freeNativeMemory()
    return bytes
}

/**
 * Test group F: [BufferCodecConverter] defect regressions.
 *
 * - `deserialize` must free its input buffer exactly once, on both the success and decode-throws
 *   paths (the buffer must never leak native memory, and must never be double-freed).
 * - `serialize` must return `null` — not throw `ClassCastException` — when the requested
 *   [io.ktor.util.reflect.TypeInfo] doesn't match the converter's bound type, so Ktor falls
 *   through to the next registered converter.
 */
class BufferCodecConverterTest {
    @Test
    fun deserialize_freesInputBuffer_onSuccess() =
        runTest {
            val factory = AllocFreeCountingFactory(BufferFactory.managed())
            val converter = BufferCodecConverter(CommandCodec, factory = factory)
            val channel = ByteReadChannel(Command.Ping(ts = 0x1122_3344_5566_7788L).encodedBytes())

            val result = converter.deserialize(Charsets.UTF_8, typeInfo<Command>(), channel)

            assertEquals(Command.Ping(ts = 0x1122_3344_5566_7788L), result)
            assertEquals(1, factory.allocations, "deserialize must allocate exactly one input buffer")
            assertEquals(factory.allocations, factory.frees, "every allocated input buffer must be freed exactly once")
        }

    @Test
    fun deserialize_freesInputBuffer_whenDecodeThrows() =
        runTest {
            val factory = AllocFreeCountingFactory(BufferFactory.managed())
            val converter = BufferCodecConverter(ThrowingDecodeCodec(CommandCodec), factory = factory)
            val channel = ByteReadChannel(Command.Ping(ts = 1L).encodedBytes())

            assertFailsWith<IllegalStateException> {
                converter.deserialize(Charsets.UTF_8, typeInfo<Command>(), channel)
            }

            assertEquals(1, factory.allocations, "deserialize must allocate exactly one input buffer")
            assertEquals(1, factory.frees, "the input buffer must be freed even when decode throws")
        }

    @Test
    fun serialize_returnsNull_whenTypeInfoDoesNotMatch() =
        runTest {
            val converter = BufferCodecConverter(CommandCodec)

            val result =
                converter.serialize(
                    contentType = ContentType.Application.OctetStream,
                    charset = Charsets.UTF_8,
                    typeInfo = typeInfo<TwoStrings>(),
                    value = TwoStrings(first = "hi", second = "yo"),
                )

            assertNull(result, "mismatched TypeInfo must fall through to the next converter, not throw")
        }

    @Test
    fun serialize_encodesValue_whenTypeInfoMatches() =
        runTest {
            val converter = BufferCodecConverter(CommandCodec)
            val message = Command.Echo(msg = "round-trip")

            val result =
                converter.serialize(
                    contentType = ContentType.Application.OctetStream,
                    charset = Charsets.UTF_8,
                    typeInfo = typeInfo<Command>(),
                    value = message,
                )

            assertNotNull(result)
            val bytes = (result as OutgoingContent.ByteArrayContent).bytes()
            assertEquals(message, CommandCodec.decode(BufferFactory.Default.wrap(bytes), DecodeContext.Empty))
        }
}
