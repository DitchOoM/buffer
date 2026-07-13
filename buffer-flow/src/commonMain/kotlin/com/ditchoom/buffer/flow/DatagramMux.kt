package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow

/**
 * A multiplexed datagram connection: many independent logical datagram flows over one underlying
 * datagram channel, demuxed by a leading flow id.
 *
 * This is the **unreliable-transport analogue of [ByteStreamMux]**. Where [ByteStreamMux] multiplexes
 * reliable byte streams (QUIC streams), [DatagramMux] multiplexes unreliable datagram flows — exactly
 * what WebTransport does today by hand, prefixing each datagram with a session/flow id and routing it
 * to a per-session channel. Datagrams **bypass the reliable stream mux** ([ByteStreamMux] only does
 * `openBidirectional`/`openUnidirectional`), so there is no conflict: this is the parallel primitive.
 *
 * The flow-id *semantics* (how the id is encoded on the wire, session lifecycle) live in the consumer
 * (`:socket-quic` / `socket-webtransport`); this interface is only the shared shape (decision §10.4).
 *
 * **Thread safety:** implementations are NOT assumed thread-safe; external synchronization is required
 * for concurrent [open] / [accept] unless an implementation documents otherwise.
 */
@ExperimentalDatagramApi
interface DatagramMux {
    /** Open a logical datagram flow identified by [flowId] over the underlying datagram channel. */
    fun open(flowId: Long): DatagramChannel

    /** Peer-initiated datagram flows, demuxed by their leading id. */
    fun accept(): Flow<DatagramChannel>
}
