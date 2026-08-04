package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer

/**
 * The receive half of a datagram endpoint — the unreliable, pre-framed, **addressed** analogue of
 * [ByteSource].
 *
 * There is **no framing driver** here (no `StreamProcessor`, no `peekFrameSize`): the kernel already
 * delivers one whole message per [receive], so a datagram source never dissolves message boundaries
 * the way the byte-stream shape does. [receive] returns exactly one [Datagram] (with its source
 * [Datagram.peer]) or a [DatagramReadResult.Closed].
 *
 * The addressing mode is a *type*, not a runtime state: a [ConnectedDatagramSource] has one fixed
 * [ConnectedDatagramSource.peer]; an [AddressedDatagramSource] receives from many peers and is bound
 * by construction. This base carries only the mode-agnostic receive machinery — each mode-specific
 * field lives solely on the refinement that can honor it, so there is no `null` to interrogate. One
 * class deliberately cannot implement both refinements (their `localAddress` types conflict): an
 * endpoint's addressing mode is fixed at construction.
 *
 * **Thread safety:** implementations are NOT assumed thread-safe; confine [receive] to one coroutine.
 */
@ExperimentalDatagramApi
interface DatagramSource {
    /** Whether the source is open. Once closed, [receive] yields [DatagramReadResult.Closed]. */
    val isOpen: Boolean

    /** The read-side control-plane capabilities of this source (§7.2). Consult, never assume. */
    val capabilities: DatagramCapabilities

    /** Receives the next datagram, or [DatagramReadResult.Closed] once the source ends. */
    suspend fun receive(): DatagramReadResult

    /**
     * Batching hook: receive up to [max] datagrams (recvmmsg / GRO where the platform offers it).
     * The default fans out to [receive], blocking until [max] datagrams have arrived or the source
     * closes; the returned list ends at the first [DatagramReadResult.Closed]. Real actuals override
     * with a single syscall.
     */
    suspend fun receiveBatch(max: Int): List<DatagramReadResult> {
        require(max > 0) { "max must be positive: $max" }
        val out = ArrayList<DatagramReadResult>(max)
        repeat(max) {
            val r = receive()
            out.add(r)
            if (r is DatagramReadResult.Closed) return out
        }
        return out
    }
}

/**
 * The receive half of a **connected** (single fixed peer) datagram endpoint — a `connect()`ed UDP
 * socket, a QUIC/WebTransport datagram flow. Every received [Datagram.peer] equals [peer].
 */
@ExperimentalDatagramApi
interface ConnectedDatagramSource : DatagramSource {
    /** The fixed remote peer, known at construction — hoisted here because only this shape has one. */
    val peer: SocketAddress

    /**
     * The local endpoint, when the transport surfaces one. A `connect()`ed UDP socket knows it
     * ([LocalAddress.of]); a QUIC datagram flow does not surface the underlying UDP endpoint and
     * reports [LocalAddress.Unknown]. Typed absent state, never `null` (§7.2).
     */
    val localAddress: LocalAddress
}

/**
 * The receive half of an **addressed** (many peers) datagram endpoint — raw UDP for SFU/TURN/ICE.
 * [Datagram.peer] is the per-packet source. Bound by construction, so [localAddress] is simply
 * non-null: ICE local-candidate gathering reads it with no unwrap. An implementation that cannot
 * learn its bound address (getsockname failure) must fail at construction, not report an absent
 * state here.
 */
@ExperimentalDatagramApi
interface AddressedDatagramSource : DatagramSource {
    /** The bound local endpoint (for ICE local-candidate gathering). Non-null by construction. */
    val localAddress: SocketAddress
}

/**
 * The send half of a datagram endpoint — the analogue of [ByteSink].
 *
 * The send verb lives only on [ConnectedDatagramSink] and [AddressedDatagramSink] — there is no
 * mode-agnostic send, because the two modes have different arities: a connected sink has no address
 * parameter at all and an addressed sink requires one. The old `to: SocketAddress? = null` conflation
 * allowed both a runtime "no destination" error and a silently-ignored address; neither is
 * expressible now.
 *
 * ## The payload contract, for every send on either refinement
 *
 * **A send reads its payload without consuming it.** The send transmits the readable window
 * `[position, limit)` and leaves the caller's cursor exactly where it found it, so one buffer can be
 * sent again — to the next peer in a fan-out, or as a retransmit — with no `resetForRead()` in
 * between. A datagram send is all-or-nothing, so unlike a stream write (whose cursor is the resume
 * point for a partial write's residue) a post-send cursor would carry no information; all that
 * consuming could add is a forgotten reset silently putting a legal zero-length datagram on the wire.
 * An implementation whose platform primitive is itself consuming (java.nio's `write`/`send` advance
 * position) must therefore transmit through a *view*, never the caller's buffer. Enforced by
 * `DatagramChannelConformanceTests.sendDoesNotConsumeCallerBuffer`.
 *
 * **Ownership is not transferred**: the sink borrows the payload for the duration of the call and
 * never frees it — the caller frees it, or returns it to its pool, afterwards. Note this runs the
 * opposite way from the receive side, where [Datagram.payload] ownership transfers *out*; either way
 * the buffer belongs to whoever allocated it. A borrowing sink must also take no lasting reference on
 * the payload: on a pooled buffer, a `slice()` retained past the call pins the chunk and quietly
 * drains the pool.
 *
 * **Thread safety:** implementations are NOT assumed thread-safe; confine sends to one coroutine.
 */
@ExperimentalDatagramApi
interface DatagramSink {
    /** Whether the sink is open. */
    val isOpen: Boolean

    /**
     * The largest payload a single send can carry — the link MTU for raw UDP, or the negotiated
     * `max_datagram_size` for QUIC/WebTransport datagrams. A payload larger than this may be rejected
     * or dropped.
     */
    val maxWritableSize: Int

    /** The send-side control-plane capabilities of this sink (§7.2). Consult, never assume. */
    val capabilities: DatagramCapabilities

    /** Close the send side. Idempotent. Defaults to a no-op (a simple sink needs nothing). */
    fun close() {}
}

/**
 * The send half of a **connected** datagram endpoint. There is no destination parameter — the peer
 * was fixed at construction, so §4's per-send zero-alloc concern is moot (nothing to resolve, ever).
 */
@ExperimentalDatagramApi
interface ConnectedDatagramSink : DatagramSink {
    /** The fixed remote peer every [send] targets. */
    val peer: SocketAddress

    /**
     * Sends [payload] to the fixed [peer] with the control plane [options]. [payload] is read but
     * neither consumed nor owned — see the payload contract on [DatagramSink] for both axes.
     * [options] defaults to the shared [DatagramSendOptions.Default] to keep the hot path
     * allocation-free.
     */
    suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions = DatagramSendOptions.Default,
    )

    /**
     * Batching hook (§10.5): send many payloads to the fixed peer with ONE shared control plane —
     * exactly the UDP GSO contract (uniform destination, uniform options, equal-segment fan-out),
     * which is the only kernel batching mechanism a connected sender has. No per-element wrapper
     * type, so a batch allocates nothing beyond the caller's list; a caller needing per-datagram
     * options on a connected sink loops [send]. The default fans out to [send]; real actuals
     * override with a single syscall.
     */
    suspend fun sendBatch(
        payloads: List<ReadBuffer>,
        options: DatagramSendOptions = DatagramSendOptions.Default,
    ) {
        for (p in payloads) send(p, options)
    }
}

/**
 * The send half of an **addressed** datagram endpoint. [to] is REQUIRED: the 3-arg send is
 * deliberate (§4) — a pre-resolved [SocketAddress] owns its platform representation, so sending to
 * many distinct peers is zero-alloc (no per-packet resolve, no address reconstruction, and now no
 * nullable branch either).
 */
@ExperimentalDatagramApi
interface AddressedDatagramSink : DatagramSink {
    /**
     * Sends [payload] to [to] with the control plane [options]. [payload] is read but neither
     * consumed nor owned — see the payload contract on [DatagramSink] for both axes. Fan-out to many
     * peers is exactly what the non-consuming cursor rule buys: send the same buffer to each [to]
     * with no `resetForRead()` in between.
     */
    suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions = DatagramSendOptions.Default,
    )

    /**
     * Batching hook (§10.5): sendmmsg-shaped — each element carries its own destination and control
     * plane. The default fans out to [send]; real actuals override with a single syscall.
     */
    suspend fun sendBatch(datagrams: List<OutboundDatagram>) {
        for (d in datagrams) send(d.payload, d.to, d.options)
    }
}

/**
 * A bidirectional datagram endpoint, mode-agnostic. Mirrors [ByteStream] over the byte trichotomy:
 * the tightest type per direction AND per addressing mode. You cannot send through this base type —
 * obtain a [ConnectedDatagramChannel] or [AddressedDatagramChannel], which know their destination
 * story. (Receiving is mode-agnostic, so [receive] stays reachable here.)
 */
@ExperimentalDatagramApi
interface DatagramChannel :
    DatagramSource,
    DatagramSink {
    /** Close the whole channel (re-abstracts [DatagramSink.close], which is send-side-only). */
    override fun close()
}

/**
 * A duplex **connected** datagram endpoint — a `connect()`ed UDP socket, a QUIC/WebTransport
 * datagram flow. One fixed [peer]; sends take no address; [localAddress] is the typed maybe-known
 * state. What `UdpSocket.connect()` and [DatagramMux] flows return.
 */
@ExperimentalDatagramApi
interface ConnectedDatagramChannel :
    DatagramChannel,
    ConnectedDatagramSource,
    ConnectedDatagramSink

/**
 * A duplex **addressed** datagram endpoint — a bound UDP socket serving many peers (SFU/TURN/ICE,
 * a shared QUIC server socket, multicast). Sends require [to]; bound by construction, so
 * [localAddress] is plainly non-null. What `UdpSocket.bind()`/`bindMulticast()` return.
 */
@ExperimentalDatagramApi
interface AddressedDatagramChannel :
    DatagramChannel,
    AddressedDatagramSource,
    AddressedDatagramSink
