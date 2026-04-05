package com.ditchoom.buffer.flow

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** In-memory [Connection] backed by a [Channel] for testing. */
private class MemoryConnection<T>(
    override val id: Long,
    private val outbound: Channel<T>,
    private val inbound: Channel<T>,
) : Connection<T> {
    override suspend fun send(message: T) = outbound.send(message)

    override fun receive(): Flow<T> = inbound.receiveAsFlow()

    override suspend fun close() {
        outbound.close()
        inbound.close()
    }
}

/** Creates a pair of connected in-memory connections. */
private fun <T> memoryConnectionPair(id: Long = 0): Pair<MemoryConnection<T>, MemoryConnection<T>> {
    val aToB = Channel<T>(Channel.UNLIMITED)
    val bToA = Channel<T>(Channel.UNLIMITED)
    return MemoryConnection<T>(id, aToB, bToA) to MemoryConnection<T>(id, bToA, aToB)
}

class ConnectionTest {
    @Test
    fun senderSamConversion() =
        runTest {
            val messages = mutableListOf<String>()
            val sender = Sender<String> { msg -> messages.add(msg) }
            sender.send("hello")
            assertEquals(listOf("hello"), messages)
        }

    @Test
    fun receiverSamConversion() =
        runTest {
            val receiver = Receiver { flowOf("a", "b") }
            val first = receiver.receive().first()
            assertEquals("a", first)
        }

    @Test
    fun connectionRoundTrip() =
        runTest {
            val (client, server) = memoryConnectionPair<String>()
            client.send("ping")
            val received = server.receive().first()
            assertEquals("ping", received)
            client.close()
            server.close()
        }

    @Test
    fun connectionId() =
        runTest {
            val (client, _) = memoryConnectionPair<String>(id = 42)
            assertEquals(42L, client.id)
        }

    @Test
    fun connectionPassableAsSender() =
        runTest {
            val (client, server) = memoryConnectionPair<Int>()
            val sender: Sender<Int> = client
            sender.send(99)
            assertEquals(99, server.receive().first())
            client.close()
            server.close()
        }

    @Test
    fun connectionPassableAsReceiver() =
        runTest {
            val (client, server) = memoryConnectionPair<Int>()
            client.send(77)
            val receiver: Receiver<Int> = server
            assertEquals(77, receiver.receive().first())
            client.close()
            server.close()
        }
}
