package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maps a [Sender] of type [A] to a [Sender] of type [B].
 *
 * The [transform] converts outbound messages from the caller's type [B]
 * to the delegate's type [A] before sending.
 *
 * ```kotlin
 * val textSender: Sender<String> = wsSender.contramap { text -> WebSocketMessage.Text(text) }
 * textSender.send("hello") // sends WebSocketMessage.Text("hello")
 * ```
 */
fun <A, B> Sender<A>.contramap(transform: suspend (B) -> A): Sender<B> =
    Sender { send(transform(it)) }

/**
 * Maps a [Receiver] of type [A] to a [Receiver] of type [B].
 *
 * The [transform] converts inbound messages from the delegate's type [A]
 * to the caller's type [B] on receive.
 *
 * ```kotlin
 * val textReceiver: Receiver<String> = wsReceiver.map { (it as WebSocketMessage.Text).value }
 * textReceiver.receive().collect { text -> println(text) }
 * ```
 */
fun <A, B> Receiver<A>.map(transform: suspend (A) -> B): Receiver<B> =
    Receiver { receive().map(transform) }

/**
 * Maps a [Connection] of type [A] to a [Connection] of type [B].
 *
 * Combines [Sender.contramap] and [Receiver.map] with lifecycle delegation.
 * This is the primary way to layer protocols:
 *
 * ```kotlin
 * val jsonConn: Connection<ChatMessage> = wsConn.map(
 *     encode = { msg -> WebSocketMessage.Text(Json.encodeToString(msg)) },
 *     decode = { (it as WebSocketMessage.Text).value.let { Json.decodeFromString(it) } },
 * )
 * ```
 *
 * Works with any [Connection] including `ReconnectingConnection` — the mapping
 * is applied per-message, so reconnection is transparent to the mapped layer.
 */
fun <A, B> Connection<A>.map(
    encode: suspend (B) -> A,
    decode: suspend (A) -> B,
): Connection<B> =
    object : Connection<B> {
        override val id: Long get() = this@map.id
        override suspend fun send(message: B) = this@map.send(encode(message))
        override fun receive(): Flow<B> = this@map.receive().map { decode(it) }
        override suspend fun close() = this@map.close()
    }
