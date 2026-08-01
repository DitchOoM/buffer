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
 * - **Boundaries preserved** — each send delivers exactly one [Datagram]; nothing is concatenated.
 * - **Per-packet source** — the delivered [Datagram.peer] is the *sender's* local address.
 * - **Copy on send** — the payload is copied into a fresh buffer the receiver owns, so the caller may
 *   keep/pool its buffer (a real socket copies into the kernel).
 * - **Unreliable** — a datagram addressed to an unbound endpoint is silently dropped.
 * - **Capability-honest** — a read-side control-plane field is carried only when [capabilities]
 *   advertises it; otherwise the receiver sees the §7.2 typed absent state.
 * - **Mode is a type** — [bind] returns an [AddressedDatagramChannel]; [connectedPair] returns two
 *   [ConnectedDatagramChannel]s whose sends target the fixed peer with no address parameter.
 */
@OptIn(ExperimentalDatagramApi::class)
internal class MemoryDatagramNetwork(
    val capabilities: DatagramCapabilities = FullMemoryCapabilities,
) {
    private val endpoints = HashMap<SocketAddress, Channel<Datagram>>()

    /** Bind an **addressed** endpoint at [local]; sends addressed to [local] arrive here. */
    fun bind(local: SocketAddress): MemoryAddressedDatagramChannel {
        val inbound = Channel<Datagram>(Channel.UNLIMITED)
        endpoints[local] = inbound
        return MemoryAddressedDatagramChannel(MemoryDatagramCore(local, inbound, this, capabilities))
    }

    /** A **connected** pair: each channel's sends reach the other; peers are fixed by the type. */
    fun connectedPair(
        addrA: SocketAddress,
        addrB: SocketAddress,
    ): Pair<MemoryConnectedDatagramChannel, MemoryConnectedDatagramChannel> {
        val inA = Channel<Datagram>(Channel.UNLIMITED)
        val inB = Channel<Datagram>(Channel.UNLIMITED)
        endpoints[addrA] = inA
        endpoints[addrB] = inB
        val a = MemoryConnectedDatagramChannel(MemoryDatagramCore(addrA, inA, this, capabilities), peer = addrB)
        val b = MemoryConnectedDatagramChannel(MemoryDatagramCore(addrB, inB, this, capabilities), peer = addrA)
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

/**
 * The shared mailbox / capability / close plumbing behind both memory channel modes. The addressing
 * mode itself lives ONLY in the thin wrappers ([MemoryAddressedDatagramChannel] /
 * [MemoryConnectedDatagramChannel]) — the core has no `to ?: connectedPeer` fallback because the
 * destination always arrives already resolved by the wrapper's type.
 */
@OptIn(ExperimentalDatagramApi::class)
internal class MemoryDatagramCore(
    val localAddress: SocketAddress,
    private val inbound: Channel<Datagram>,
    private val network: MemoryDatagramNetwork,
    val capabilities: DatagramCapabilities,
) {
    private var closed = false
    private var closeReason: DatagramCloseReason = DatagramCloseReason.Normal

    val isOpen: Boolean get() = !closed && !inbound.isClosedForReceive

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). */
    val maxWritableSize: Int = 65507

    suspend fun receive(): DatagramReadResult {
        val result = inbound.receiveCatching()
        val datagram = result.getOrNull()
        return when {
            datagram != null -> DatagramReadResult.Received(datagram)
            else -> DatagramReadResult.Closed(closeReason)
        }
    }

    fun sendTo(
        dest: SocketAddress,
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        check(!closed) { "sink is closed" }

        // Copy the payload so the caller keeps ownership of its buffer.
        val slice = payload.slice()
        val bytes = slice.readByteArray(slice.remaining())
        val delivered: PlatformBuffer = BufferFactory.Default.wrap(bytes)

        // Carry each control-plane field only if the capability set advertises both ends of it.
        val ecn =
            if (capabilities.ecnSend && capabilities.ecnReceive && options.ecn != EcnPreference.OsDefault) {
                // The stamped preference arrives as the read-side verdict — the loopback of a real stack.
                Ecn.fromCodepoint(options.ecn.codepoint)
            } else {
                Ecn.Unknown
            }
        val hopLimit =
            if (capabilities.hopLimitSend && capabilities.hopLimitReceive && options.hopLimit >= 0) {
                HopLimit.of(options.hopLimit)
            } else {
                HopLimit.Unknown
            }
        val localAddr =
            if (capabilities.localAddressReceive) {
                LocalAddress.of(options.fromLocal ?: localAddress)
            } else {
                LocalAddress.Unknown
            }

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

    fun close(reason: DatagramCloseReason = DatagramCloseReason.Normal) {
        closed = true
        closeReason = reason
        inbound.close()
    }
}

/** The addressed memory endpoint: bound by construction, sends REQUIRE `to`. */
@OptIn(ExperimentalDatagramApi::class)
internal class MemoryAddressedDatagramChannel(
    private val core: MemoryDatagramCore,
) : AddressedDatagramChannel {
    override val localAddress: SocketAddress get() = core.localAddress

    override val isOpen: Boolean get() = core.isOpen

    override val capabilities: DatagramCapabilities get() = core.capabilities

    override val maxWritableSize: Int get() = core.maxWritableSize

    override suspend fun receive(): DatagramReadResult = core.receive()

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) = core.sendTo(to, payload, options)

    override fun close() = core.close()

    /** Test hook: close with an explicit typed [reason] so reason propagation can be asserted. */
    fun close(reason: DatagramCloseReason) = core.close(reason)
}

/** The connected memory endpoint: one fixed [peer], sends take no address. */
@OptIn(ExperimentalDatagramApi::class)
internal class MemoryConnectedDatagramChannel(
    private val core: MemoryDatagramCore,
    override val peer: SocketAddress,
) : ConnectedDatagramChannel {
    /** The memory pair knows its local endpoint, so it is a known [LocalAddress]. */
    override val localAddress: LocalAddress = LocalAddress.of(core.localAddress)

    override val isOpen: Boolean get() = core.isOpen

    override val capabilities: DatagramCapabilities get() = core.capabilities

    override val maxWritableSize: Int get() = core.maxWritableSize

    override suspend fun receive(): DatagramReadResult = core.receive()

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) = core.sendTo(peer, payload, options)

    override fun close() = core.close()

    /** Test hook: close with an explicit typed [reason] so reason propagation can be asserted. */
    fun close(reason: DatagramCloseReason) = core.close(reason)
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
