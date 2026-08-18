package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * Datagram-native typed views.
 *
 * A Codec plugs in UNCHANGED — each datagram is already one whole message, so the codec's whole-buffer
 * encode/decode is called directly. What does not carry over from the byte trichotomy is the stream
 * framing driver (StreamProcessor / peekFrameSize): feeding pre-framed datagrams through it would
 * concatenate them and dissolve kernel boundaries. These adapters are therefore much simpler than
 * ByteSource.typed — no processor, no pool.
 */

/**
 * Views this **connected** datagram source as a typed [Receiver] of [T]: each received datagram's
 * payload is decoded with [codec] and emitted; the source peer is dropped (enforced by the receiver
 * type — every peer is the fixed [ConnectedDatagramSource.peer]). A [DatagramReadResult.Closed]
 * completes the flow. Each payload buffer is freed after decode.
 */
@ExperimentalDatagramApi
public fun <T> ConnectedDatagramSource.typed(
    codec: Codec<T>,
    context: DecodeContext = DecodeContext.Empty,
): Receiver<T> =
    Receiver {
        flow {
            while (true) {
                when (val r = receive()) {
                    is DatagramReadResult.Received -> {
                        val payload = r.datagram.payload
                        val value =
                            try {
                                codec.decode(payload, context)
                            } finally {
                                payload.freeNativeMemory()
                            }
                        emit(value)
                    }
                    is DatagramReadResult.Closed -> break
                }
            }
        }
    }

/**
 * Views this **addressed** datagram source as a typed [Receiver] of [Addressed] messages:
 * each received datagram is decoded with [codec] and tagged with its source peer — exactly what an
 * SFU/ICE stack wants (`Receiver<Addressed<StunMessage>>`, `Receiver<Addressed<RtpPacket>>`). A
 * [DatagramReadResult.Closed] completes the flow. Each payload buffer is freed after decode.
 */
@ExperimentalDatagramApi
public fun <T> AddressedDatagramSource.typedAddressed(
    codec: Codec<T>,
    context: DecodeContext = DecodeContext.Empty,
): Receiver<Addressed<T>> =
    Receiver {
        flow {
            while (true) {
                when (val r = receive()) {
                    is DatagramReadResult.Received -> {
                        val payload = r.datagram.payload
                        val value =
                            try {
                                codec.decode(payload, context)
                            } finally {
                                payload.freeNativeMemory()
                            }
                        emit(Addressed(value, r.datagram.peer))
                    }
                    is DatagramReadResult.Closed -> break
                }
            }
        }
    }

/**
 * Views this **connected** datagram sink as a typed [Sender] of [T]: each message is encoded with
 * [codec] into a fresh buffer and sent to the fixed [ConnectedDatagramSink.peer]. The encode buffer
 * is freed after each send. [Sender.close] delegates to [DatagramSink.close].
 */
@ExperimentalDatagramApi
public fun <T> ConnectedDatagramSink.typed(
    codec: Codec<T>,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
    options: DatagramSendOptions = DatagramSendOptions.Default,
): Sender<T> =
    object : Sender<T> {
        override suspend fun send(message: T) {
            val buffer = codec.encodeToPlatformBuffer(message, factory, context)
            try {
                this@typed.send(buffer, options = options)
            } finally {
                buffer.freeNativeMemory()
            }
        }

        override suspend fun close() = this@typed.close()
    }

/**
 * Encodes [message] with [codec] and sends it to the fixed [ConnectedDatagramSink.peer] with the
 * control plane [options] — the connected one-shot. The encode buffer is freed after the send.
 */
@ExperimentalDatagramApi
public suspend fun <T> ConnectedDatagramSink.typedSend(
    message: T,
    codec: Codec<T>,
    options: DatagramSendOptions = DatagramSendOptions.Default,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
) {
    val buffer = codec.encodeToPlatformBuffer(message, factory, context)
    try {
        send(buffer, options)
    } finally {
        buffer.freeNativeMemory()
    }
}

/**
 * Encodes [message] with [codec] and sends it to the REQUIRED [to] (the addressed, many-dest case)
 * with the control plane [options]. The encode buffer is freed after the send. Mirrors
 * [ConnectedDatagramSink.typed] for the case where the destination varies per message (SFU/TURN
 * egress).
 */
@ExperimentalDatagramApi
public suspend fun <T> AddressedDatagramSink.typedSend(
    message: T,
    codec: Codec<T>,
    to: SocketAddress,
    options: DatagramSendOptions = DatagramSendOptions.Default,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
) {
    val buffer = codec.encodeToPlatformBuffer(message, factory, context)
    try {
        send(buffer, to, options)
    } finally {
        buffer.freeNativeMemory()
    }
}

/**
 * Views this **connected** duplex [ConnectedDatagramChannel] as a typed [Connection] of [T]: the
 * receive half decodes inbound datagrams (see [typed] on [ConnectedDatagramSource]) and the send half
 * encodes outbound messages to the fixed peer (see [typed] on [ConnectedDatagramSink]).
 * [Connection.close] closes the channel.
 */
@ExperimentalDatagramApi
public fun <T> ConnectedDatagramChannel.typed(
    codec: Codec<T>,
    factory: BufferFactory = BufferFactory.Default,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
    options: DatagramSendOptions = DatagramSendOptions.Default,
): Connection<T> {
    val channel = this
    val receiver = (channel as ConnectedDatagramSource).typed(codec, decodeContext)
    val sender = (channel as ConnectedDatagramSink).typed(codec, factory, encodeContext, options)
    return object : Connection<T> {
        override val id: Long get() = 0L

        override suspend fun send(message: T) = sender.send(message)

        override fun receive(): Flow<T> = receiver.receive()

        override suspend fun close() = channel.close()

        // Explicit even though it matches the inherited default: a datagram channel holds no
        // outbound queue, so there is nothing for abort to drop that close would have drained.
        // Stated rather than inherited so the next change here has to decide deliberately —
        // any decorator overriding close() overrides abort().
        override suspend fun abort() = channel.close()
    }
}
