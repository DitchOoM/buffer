package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionByteStreamTest {
    private fun testConnection(): Triple<Channel<ReadBuffer>, Channel<ReadBuffer>, Connection<ReadBuffer>> {
        val inbound = Channel<ReadBuffer>(Channel.UNLIMITED)
        val outbound = Channel<ReadBuffer>(Channel.UNLIMITED)
        val connection =
            object : Connection<ReadBuffer> {
                override val id = 0L

                override fun receive(): Flow<ReadBuffer> = inbound.receiveAsFlow()

                override suspend fun send(message: ReadBuffer) = outbound.send(message)

                override suspend fun close() {
                    inbound.close()
                    outbound.close()
                }
            }
        return Triple(inbound, outbound, connection)
    }

    private fun bufferOf(text: String): ReadBuffer {
        val buf = BufferFactory.Default.allocate(text.length)
        buf.writeString(text)
        buf.resetForRead()
        return buf
    }

    @Test
    fun writePassesThroughToConnection() =
        runTest {
            val (_, outbound, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            val written = stream.write(bufferOf("hello"))
            assertEquals(5, written.count)

            val sent = outbound.receive()
            assertEquals(5, sent.remaining())

            stream.close()
        }

    @Test
    fun readPassesThroughFromConnection() =
        runTest {
            val (inbound, _, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            inbound.send(bufferOf("world"))

            val result = stream.read()
            assertIs<ReadResult.Data>(result)
            assertEquals(5, result.buffer.remaining())

            stream.close()
        }

    @Test
    fun roundTrip() =
        runTest {
            val (inbound, outbound, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            // Write through adapter
            stream.write(bufferOf("hello"))
            val sent = outbound.receive()
            assertEquals(5, sent.remaining())

            // Read through adapter
            inbound.send(bufferOf("world"))
            val result = stream.read()
            assertIs<ReadResult.Data>(result)
            assertEquals(5, result.buffer.remaining())

            stream.close()
        }

    @Test
    fun extractSkipsNulls() =
        runTest {
            val (inbound, _, connection) = testConnection()

            // Wrapper type that has data and control messages
            data class Message(
                val payload: ReadBuffer?,
                val isControl: Boolean,
            )

            val wrappedConn =
                object : Connection<Message> {
                    override val id = 0L

                    override fun receive(): Flow<Message> =
                        inbound.receiveAsFlow().let { flow ->
                            kotlinx.coroutines.flow.flow {
                                flow.collect { buf ->
                                    // Simulate: every other message is a control frame
                                    emit(Message(null, isControl = true))
                                    emit(Message(buf, isControl = false))
                                }
                            }
                        }

                    override suspend fun send(message: Message) {}

                    override suspend fun close() {
                        inbound.close()
                    }
                }

            val stream =
                wrappedConn.asByteStream(
                    scope = this,
                    extract = { it.payload },
                    wrap = { Message(it, isControl = false) },
                )

            inbound.send(bufferOf("data"))

            // Should skip the control message and return the data message
            val result = stream.read()
            assertIs<ReadResult.Data>(result)
            assertEquals(4, result.buffer.remaining())

            stream.close()
        }

    @Test
    fun closeSignalsEnd() =
        runTest {
            val (inbound, _, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            // Close the inbound channel to simulate connection end
            inbound.close()

            val result = stream.read()
            assertIs<ReadResult.End>(result)

            stream.close()
        }

    @Test
    fun isOpenReflectsState() =
        runTest {
            val (_, _, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            assertTrue(stream.isOpen)

            stream.close()
            // Give the collector coroutine time to cancel
            kotlinx.coroutines.yield()
            assertFalse(stream.isOpen)
        }

    @Test
    fun multipleReadsInOrder() =
        runTest {
            val (inbound, _, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            inbound.send(bufferOf("one"))
            inbound.send(bufferOf("two"))
            inbound.send(bufferOf("three"))

            val r1 = stream.read()
            assertIs<ReadResult.Data>(r1)
            assertEquals(3, r1.buffer.remaining())

            val r2 = stream.read()
            assertIs<ReadResult.Data>(r2)
            assertEquals(3, r2.buffer.remaining())

            val r3 = stream.read()
            assertIs<ReadResult.Data>(r3)
            assertEquals(5, r3.buffer.remaining())

            stream.close()
        }

    @Test
    fun multipleWrites() =
        runTest {
            val (_, outbound, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            assertEquals(3, stream.write(bufferOf("one")).count)
            assertEquals(3, stream.write(bufferOf("two")).count)

            assertEquals(3, outbound.receive().remaining())
            assertEquals(3, outbound.receive().remaining())

            stream.close()
        }

    @Test
    fun writeGathered() =
        runTest {
            val (_, outbound, connection) = testConnection()
            val stream =
                connection.asByteStream(
                    scope = this,
                    extract = { it },
                    wrap = { it },
                )

            val result = stream.writeGathered(listOf(bufferOf("ab"), bufferOf("cde")))
            assertEquals(5, result.count)

            assertEquals(2, outbound.receive().remaining())
            assertEquals(3, outbound.receive().remaining())

            stream.close()
        }
}
