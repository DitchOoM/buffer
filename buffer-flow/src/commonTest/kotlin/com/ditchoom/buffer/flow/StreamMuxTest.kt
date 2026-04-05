package com.ditchoom.buffer.flow

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** In-memory [StreamMux] for testing tightest-type returns. */
private class MemoryStreamMux<T> : StreamMux<T> {
    private var nextId = 0L
    private val bidiQueue = Channel<MemoryConnection<T>>(Channel.UNLIMITED)
    private val uniQueue = Channel<Channel<T>>(Channel.UNLIMITED)

    override suspend fun openBidirectional(): Connection<T> {
        val id = nextId++
        val aToB = Channel<T>(Channel.UNLIMITED)
        val bToA = Channel<T>(Channel.UNLIMITED)
        val clientSide = MemoryConnection<T>(id, aToB, bToA)
        val serverSide = MemoryConnection<T>(id, bToA, aToB)
        bidiQueue.send(serverSide)
        return clientSide
    }

    override suspend fun openUnidirectional(): Sender<T> {
        val ch = Channel<T>(Channel.UNLIMITED)
        uniQueue.send(ch)
        return Sender { msg -> ch.send(msg) }
    }

    override suspend fun acceptBidirectional(): Connection<T> = bidiQueue.receive()

    override suspend fun acceptUnidirectional(): Receiver<T> {
        val ch = uniQueue.receive()
        return Receiver { ch.receiveAsFlow() }
    }

    override suspend fun close() {
        bidiQueue.close()
        uniQueue.close()
    }

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
}

class StreamMuxTest {
    @Test
    fun openBidirectionalReturnsConnection() = runTest {
        val mux = MemoryStreamMux<String>()
        val client = mux.openBidirectional()
        assertIs<Connection<String>>(client)
        val server = mux.acceptBidirectional()
        client.send("hello")
        assertEquals("hello", server.receive().first())
        client.close()
        server.close()
        mux.close()
    }

    @Test
    fun openUnidirectionalReturnsSender() = runTest {
        val mux = MemoryStreamMux<String>()
        val sender = mux.openUnidirectional()
        assertIs<Sender<String>>(sender)
        val receiver = mux.acceptUnidirectional()
        sender.send("fire-and-forget")
        assertEquals("fire-and-forget", receiver.receive().first())
        mux.close()
    }

    @Test
    fun streamIdsAreSequential() = runTest {
        val mux = MemoryStreamMux<Int>()
        val conn0 = mux.openBidirectional()
        val conn1 = mux.openBidirectional()
        assertEquals(0L, conn0.id)
        assertEquals(1L, conn1.id)
        mux.close()
    }
}
