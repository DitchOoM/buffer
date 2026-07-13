package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The datagram trichotomy **conformance contract** — the datagram analogue of the read/write-timeout
 * RFC contract suites. Every property here is what a real `:socket-udp` platform actual must satisfy;
 * against the in-memory [MemoryDatagramNetwork] the suite is green, so it is the *baseline* the (not
 * yet written) socket actuals are measured against — "red" until they exist and pass it. Control-plane
 * assertions are **capability-gated**: they run only when the endpoint advertises the capability, and
 * the absent-capability cases assert honest degradation to sentinels (§7.2).
 */
@OptIn(ExperimentalDatagramApi::class)
class DatagramChannelConformanceTests {
    private val addrA = SocketAddress.ofLiteral("10.0.0.1", 1111)
    private val addrB = SocketAddress.ofLiteral("10.0.0.2", 2222)
    private val addrC = SocketAddress.ofLiteral("10.0.0.3", 3333)

    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private fun DatagramReadResult.text(): String {
        val d = assertIs<DatagramReadResult.Received>(this).datagram
        return d.payload.readByteArray(d.payload.remaining()).decodeToString()
    }

    // ---- shape: pre-framed, addressed, unreliable ----

    @Test
    fun receivePreservesDatagramBoundaries() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            b.send(payload("one"), to = addrA)
            b.send(payload("two"), to = addrA)
            // Two sends → two whole datagrams, never concatenated into "onetwo".
            assertEquals("one", a.receive().text())
            assertEquals("two", a.receive().text())
        }

    @Test
    fun unconnectedReceiveCarriesPerPacketPeer() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            net.bind(addrB).send(payload("hi"), to = addrA)
            val d = assertIs<DatagramReadResult.Received>(a.receive()).datagram
            assertEquals(addrB, d.peer)
        }

    @Test
    fun unconnectedSendRoutesByDestination() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            val c = net.bind(addrC)
            a.send(payload("toB"), to = addrB)
            a.send(payload("toC"), to = addrC)
            assertEquals("toB", b.receive().text())
            assertEquals("toC", c.receive().text())
        }

    @Test
    fun datagramToUnboundDestinationIsDropped() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            // Nothing bound at addrC — unreliable drop, must not throw.
            a.send(payload("void"), to = addrC)
        }

    @Test
    fun connectedSendUsesFixedPeerBothDirections() =
        runTest {
            val net = MemoryDatagramNetwork()
            val (a, b) = net.connectedPair(addrA, addrB)
            a.send(payload("ping")) // to = null → fixed peer addrB
            val atB = assertIs<DatagramReadResult.Received>(b.receive()).datagram
            assertEquals("ping", atB.payload.readByteArray(atB.payload.remaining()).decodeToString())
            assertEquals(addrA, atB.peer)

            b.send(payload("pong"))
            val atA = assertIs<DatagramReadResult.Received>(a.receive()).datagram
            assertEquals(addrB, atA.peer)
        }

    // ---- lifecycle ----

    @Test
    fun closeMakesReceiveReturnClosedAndIsOpenFalse() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            assertTrue(a.isOpen)
            a.close()
            assertFalse(a.isOpen)
            assertIs<DatagramReadResult.Closed>(a.receive())
        }

    @Test
    fun sendAfterCloseThrows() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            a.close()
            assertFailsWith<IllegalStateException> { a.send(payload("x"), to = addrB) }
        }

    @Test
    fun sendDoesNotConsumeCallerBuffer() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            val buf = payload("keep")
            val before = buf.remaining()
            a.send(buf, to = addrB)
            assertEquals(before, buf.remaining(), "send must not consume the caller's payload buffer")
            assertEquals("keep", b.receive().text())
        }

    @Test
    fun maxWritableSizeIsPositive() =
        runTest {
            val net = MemoryDatagramNetwork()
            assertTrue(net.bind(addrA).maxWritableSize > 0)
        }

    // ---- control plane (capability-gated §7.2) ----

    @Test
    fun controlPlaneRoundTripsWhenAdvertised() =
        runTest {
            val net = MemoryDatagramNetwork(FullMemoryCapabilities)
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            val opts =
                DatagramSendOptions(
                    ecn = Ecn.Ect0,
                    hopLimit = 55,
                    fromLocal = addrB,
                )
            b.send(payload("cp"), to = addrA, options = opts)
            val d = assertIs<DatagramReadResult.Received>(a.receive()).datagram

            if (net.capabilities.ecnSend && net.capabilities.ecnReceive) {
                assertEquals(Ecn.Ect0, d.ecn)
            }
            if (net.capabilities.hopLimitSend && net.capabilities.hopLimitReceive) {
                assertEquals(55, d.hopLimit)
            }
            if (net.capabilities.localAddressReceive) {
                assertEquals(addrB, d.localAddress)
            }
        }

    @Test
    fun controlPlaneDegradesToSentinelsWhenAbsent() =
        runTest {
            val net = MemoryDatagramNetwork(DatagramCapabilities.None)
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            b.send(
                payload("cp"),
                to = addrA,
                options = DatagramSendOptions(ecn = Ecn.Ect0, hopLimit = 55, fromLocal = addrB),
            )
            val d = assertIs<DatagramReadResult.Received>(a.receive()).datagram
            // Absent read capabilities → sentinels, never fabricated values.
            assertEquals(Ecn.Unknown, d.ecn)
            assertEquals(-1, d.hopLimit)
            assertEquals(null, d.localAddress)
        }

    @Test
    fun dontFragmentIsNeverSilentlyClaimedWhenAbsent() =
        runTest {
            // Correctness-critical: an endpoint that can't set DF must advertise it absent, so a
            // consumer (quiche) holds its MTU floor instead of mis-fragmenting.
            assertFalse(MemoryDatagramNetwork(DatagramCapabilities.None).capabilities.dontFragment)
            assertTrue(MemoryDatagramNetwork(FullMemoryCapabilities).capabilities.dontFragment)
        }

    // ---- typed views (§5) ----

    @Test
    fun typedAddressedDecodesAndTagsPeer() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            b.typedSend("hello", WholeStringCodec, to = addrA)
            val first =
                a
                    .typedAddressed(WholeStringCodec)
                    .receive()
                    .take(1)
                    .toList()
                    .single()
            assertEquals("hello", first.value)
            assertEquals(addrB, first.peer)
        }

    @Test
    fun typedConnectedRoundTrips() =
        runTest {
            val net = MemoryDatagramNetwork()
            val (a, b) = net.connectedPair(addrA, addrB)
            val connA = a.typed(WholeStringCodec)
            connA.send("ping")
            val received =
                b
                    .typed(WholeStringCodec)
                    .receive()
                    .take(1)
                    .toList()
                    .single()
            assertEquals("ping", received)
        }

    // ---- batching hooks (§10.5) ----

    @Test
    fun sendBatchDefaultFansOut() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            a.sendBatch(
                listOf(
                    OutboundDatagram(payload("1"), to = addrB),
                    OutboundDatagram(payload("2"), to = addrB),
                    OutboundDatagram(payload("3"), to = addrB),
                ),
            )
            assertEquals("1", b.receive().text())
            assertEquals("2", b.receive().text())
            assertEquals("3", b.receive().text())
        }

    @Test
    fun receiveBatchDefaultLoops() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            val b = net.bind(addrB)
            repeat(3) { b.send(payload("m$it"), to = addrA) }
            val batch = a.receiveBatch(3)
            assertEquals(3, batch.size)
            assertEquals(listOf("m0", "m1", "m2"), batch.map { it.text() })
        }

    @Test
    fun receiveBatchStopsAtClose() =
        runTest {
            val net = MemoryDatagramNetwork()
            val a = net.bind(addrA)
            net.bind(addrB).send(payload("only"), to = addrA)
            a.close()
            val batch = a.receiveBatch(5)
            // First the buffered datagram, then Closed terminates the batch early.
            assertEquals("only", batch.first().text())
            assertIs<DatagramReadResult.Closed>(batch.last())
            assertTrue(batch.size <= 2)
        }
}

/**
 * A whole-buffer [Codec] where the datagram boundary *is* the frame: encode writes the UTF-8 bytes,
 * decode reads all remaining bytes. No length prefix, no [com.ditchoom.buffer.codec.FrameDetector]
 * participation — exactly the pre-framed shape datagrams have.
 */
@OptIn(ExperimentalDatagramApi::class)
private object WholeStringCodec : Codec<String> {
    override fun encode(
        buffer: WriteBuffer,
        value: String,
        context: EncodeContext,
    ) {
        buffer.writeString(value)
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): String = buffer.readString(buffer.remaining())

    override fun wireSize(
        value: String,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch
}
