package com.ditchoom.buffer.flow

/**
 * Sends typed messages. Used for unidirectional outbound streams.
 *
 * `fun interface` enables SAM lambda for simple cases — [send] is the sole abstract method, so the
 * default-bodied [close] and [id] below keep the SAM convenience intact:
 * ```kotlin
 * val sender = Sender<String> { msg -> channel.send(msg) }
 * ```
 *
 * The send side of a stream must *announce* its end (a FIN); the receive side merely *discovers* it
 * (the [Receiver] flow completes). That asymmetry is why [Sender] carries [close] while [Receiver]
 * does not — closing a send-only stream finishes it cleanly so the peer's [Receiver] flow completes.
 * This mirrors the byte-layer [ByteSink], which gains the same [ByteSink.close] for the same reason.
 */
fun interface Sender<in T> {
    suspend fun send(message: T)

    /**
     * Finish the send side cleanly (a stream-level FIN), so the peer's [Receiver] flow completes.
     * Idempotent. Defaults to a no-op so a plain SAM `Sender { … }` lambda needs nothing extra; real
     * unidirectional transports (QUIC / WebTransport uni streams) override it to send the FIN.
     */
    suspend fun close() {}

    /**
     * Opaque identifier for this sender's stream, for cross-layer log correlation. For multiplexed
     * transports (QUIC) this is the transport-assigned stream id; for single-stream transports it is 0.
     * Mirrors [Connection.id]. Defaults to 0.
     */
    val id: Long get() = 0L
}
