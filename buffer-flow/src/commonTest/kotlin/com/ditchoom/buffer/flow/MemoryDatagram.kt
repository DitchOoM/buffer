package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel

/**
 * An in-memory datagram "network": a routing hub keyed by destination [SocketAddress], the datagram
 * analogue of [memoryByteStreamPair]. It exists to exercise the datagram trichotomy contract with **no
 * sockets** — the same role `StubUdpChannel` plays for QUIC, and the sans-I/O seam the RFC §6
 * deterministic-simulation story stands on.
 *
 * Datagram semantics are honored faithfully:
 * - **Boundaries preserved** — each [DatagramSink.send] delivers exactly one [Datagram]; nothing is
 *   concatenated.
 * - **Per-packet source** — the delivered [Datagram.peer] is the *sender's* local address.
 * - **Copy on send** — the payload is copied into a fresh buffer the receiver owns, so the caller may
 *   keep/pool its buffer (a real socket copies into the kernel).
 * - **Unreliable** — a datagram addressed to an unbound endpoint is silently dropped.
 * - **Capability-honest** — a read-side control-plane field is carried only when [capabilities]
 *   advertises it; otherwise the receiver sees the §7.2 sentinel.
 */
@OptIn(ExperimentalDatagramApi::class)
internal class MemoryDatagramNetwork(
    val capabilities: DatagramCapabilities = FullMemoryCapabilities,
) {
    private val endpoints = HashMap<SocketAddress, Channel<Datagram>>()

    /** Bind an **unconnected** endpoint at [local]; sends addressed to [local] arrive here. */
    fun bind(local: SocketAddress): DatagramChannel {
        val inbound = Channel<Datagram>(Channel.UNLIMITED)
        endpoints[local] = inbound
        return MemoryDatagramChannel(local, inbound, this, capabilities, connectedPeer = null)
    }

    /** A **connected** pair: each channel's `to = null` sends reach the other; peers are fixed. */
    fun connectedPair(
        addrA: SocketAddress,
        addrB: SocketAddress,
    ): Pair<DatagramChannel, DatagramChannel> {
        val inA = Channel<Datagram>(Channel.UNLIMITED)
        val inB = Channel<Datagram>(Channel.UNLIMITED)
        endpoints[addrA] = inA
        endpoints[addrB] = inB
        val a = MemoryDatagramChannel(addrA, inA, this, capabilities, connectedPeer = addrB)
        val b = MemoryDatagramChannel(addrB, inB, this, capabilities, connectedPeer = addrA)
        return a to b
    }

    fun deliver(
        to: SocketAddress,
        datagram: Datagram,
    ) {
        // Unreliable: no endpoint bound at `to` → dropped, like a UDP packet into the void.
        endpoints[to]?.trySend(datagram)
    }
}

@OptIn(ExperimentalDatagramApi::class)
internal class MemoryDatagramChannel(
    override val localAddress: SocketAddress,
    private val inbound: Channel<Datagram>,
    private val network: MemoryDatagramNetwork,
    override val capabilities: DatagramCapabilities,
    private val connectedPeer: SocketAddress?,
) : DatagramChannel {
    private var closed = false

    override val isOpen: Boolean get() = !closed && !inbound.isClosedForReceive

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). */
    override val maxWritableSize: Int = 65507

    override suspend fun receive(): DatagramReadResult {
        val result = inbound.receiveCatching()
        val datagram = result.getOrNull()
        return when {
            datagram != null -> DatagramReadResult.Received(datagram)
            else -> DatagramReadResult.Closed()
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(!closed) { "sink is closed" }
        val dest = to ?: connectedPeer ?: error("no destination on an unconnected sink")

        // Copy the payload so the caller keeps ownership of its buffer.
        val slice = payload.slice()
        val bytes = slice.readByteArray(slice.remaining())
        val delivered: PlatformBuffer = BufferFactory.Default.wrap(bytes)

        // Carry each control-plane field only if the capability set advertises both ends of it.
        val ecn =
            if (capabilities.ecnSend && capabilities.ecnReceive && options.ecn != Ecn.Unknown) {
                options.ecn
            } else {
                Ecn.Unknown
            }
        val hopLimit =
            if (capabilities.hopLimitSend && capabilities.hopLimitReceive && options.hopLimit >= 0) {
                options.hopLimit
            } else {
                -1
            }
        val localAddr =
            if (capabilities.localAddressReceive) options.fromLocal ?: localAddress else null

        network.deliver(
            dest,
            Datagram(
                payload = delivered,
                peer = localAddress,
                ecn = ecn,
                localAddress = localAddr,
                hopLimit = hopLimit,
            ),
        )
    }

    override fun close() {
        closed = true
        inbound.close()
    }
}

/** A full-capability in-memory endpoint: every control-plane field round-trips through memory. */
@OptIn(ExperimentalDatagramApi::class)
internal val FullMemoryCapabilities =
    DatagramCapabilities(
        ecnSend = true,
        ecnReceive = true,
        dscpSend = true,
        dontFragment = true,
        hopLimitSend = true,
        hopLimitReceive = true,
        localAddressReceive = true,
        sourceAddressSelect = true,
        multicast = false,
    )
