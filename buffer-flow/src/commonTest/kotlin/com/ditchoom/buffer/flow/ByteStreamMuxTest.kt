package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ByteStreamMuxTest {
    private fun textBuffer(text: String): ReadBuffer {
        val bytes = text.encodeToByteArray()
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    private fun ReadResult.text(): String {
        val data = assertIs<ReadResult.Data>(this)
        return data.buffer.readByteArray(data.buffer.remaining()).decodeToString()
    }

    @Test
    fun openBidirectionalReturnsRawByteStream() =
        runTest {
            val mux = MemoryByteStreamMux()
            val client = mux.openBidirectional()
            assertIs<ByteStream>(client)
            val server = mux.acceptBidirectional()

            client.write(textBuffer("hello"))
            assertEquals("hello", server.read().text())
            server.write(textBuffer("world"))
            assertEquals("world", client.read().text())

            client.close()
            assertEquals(ReadResult.End, server.read())
        }

    @Test
    fun openUnidirectionalReturnsSinkAndAcceptReturnsSource() =
        runTest {
            val mux = MemoryByteStreamMux()
            val sink = mux.openUnidirectional()
            assertIs<ByteSink>(sink)
            val source = mux.acceptUnidirectional()
            assertIs<ByteSource>(source)

            sink.write(textBuffer("fire-and-forget"))
            sink.close()
            assertEquals("fire-and-forget", source.read().text())
            assertEquals(ReadResult.End, source.read())
        }

    @Test
    fun acceptedStreamsArriveInOpenOrder() =
        runTest {
            val mux = MemoryByteStreamMux()
            val first = mux.openUnidirectional()
            val second = mux.openUnidirectional()
            first.write(textBuffer("first"))
            second.write(textBuffer("second"))

            assertEquals("first", mux.acceptUnidirectional().read().text())
            assertEquals("second", mux.acceptUnidirectional().read().text())
        }
}
