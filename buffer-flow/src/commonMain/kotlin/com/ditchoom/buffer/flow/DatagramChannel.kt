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
 * - **Connected** (single peer): every [Datagram.peer] is the fixed peer — the QUIC/WebTransport
 *   datagram case and a `connect()`ed UDP socket.
 * - **Unconnected** (many peers): [Datagram.peer] is the per-packet source — raw UDP for SFU/TURN/ICE.
 *
 * **Thread safety:** implementations are NOT assumed thread-safe; confine [receive] to one coroutine.
 */
@ExperimentalDatagramApi
interface DatagramSource {
    /** Whether the source is open. Once closed, [receive] yields [DatagramReadResult.Closed]. */
    val isOpen: Boolean

    /** The local endpoint this source is bound to, for ICE local-candidate gathering, or `null`. */
    val localAddress: SocketAddress?

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
 * The send half of a datagram endpoint — the analogue of [ByteSink].
 *
 * The 2-arg send is deliberate (§4): [to] is a pre-resolved [SocketAddress] that owns its platform
 * representation, so sending to many distinct peers is zero-alloc — no per-packet resolve, no address
 * reconstruction.
 *
 * **Thread safety:** implementations are NOT assumed thread-safe; confine sends to one coroutine.
 */
@ExperimentalDatagramApi
interface DatagramSink {
    /** Whether the sink is open. */
    val isOpen: Boolean

    /**
     * The largest payload a single [send] can carry — the link MTU for raw UDP, or the negotiated
     * `max_datagram_size` for QUIC/WebTransport datagrams. A payload larger than this may be rejected
     * or dropped.
     */
    val maxWritableSize: Int

    /** The send-side control-plane capabilities of this sink (§7.2). Consult, never assume. */
    val capabilities: DatagramCapabilities

    /**
     * Sends [payload] to [to] (or the fixed peer when [to] is `null` on a connected sink) with the
     * control plane [options].
     *
     * [payload] is consumed as a [ReadBuffer]; ownership is not transferred (the caller may pool it).
     * [options] defaults to the shared [DatagramSendOptions.Default] to keep the hot path allocation-free.
     */
    suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress? = null,
        options: DatagramSendOptions = DatagramSendOptions.Default,
    )

    /**
     * Batching hook: send many datagrams at once (sendmmsg / GSO where the platform offers it). The
     * default fans out to [send]; real actuals override with a single syscall.
     */
    suspend fun sendBatch(datagrams: List<OutboundDatagram>) {
        for (d in datagrams) send(d.payload, d.to, d.options)
    }

    /** Close the send side. Idempotent. Defaults to a no-op (a simple sink needs nothing). */
    fun close() {}
}

/**
 * A bidirectional datagram endpoint — the common case (a bound UDP socket, a QUIC/WebTransport
 * datagram flow). Mirrors [ByteStream] over the byte trichotomy.
 *
 * A receive-only source is a [DatagramSource], a send-only sink is a [DatagramSink]; a duplex endpoint
 * is a [DatagramChannel]. No fake capabilities — the tightest type per direction, exactly as the byte
 * trichotomy.
 */
@ExperimentalDatagramApi
interface DatagramChannel :
    DatagramSource,
    DatagramSink {
    /** Close the whole channel (re-abstracts [DatagramSink.close], which is send-side-only). */
    override fun close()
}
