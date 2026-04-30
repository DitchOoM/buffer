package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

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
fun <A, B> Sender<A>.contramap(transform: (B) -> A): Sender<B> = Sender { send(transform(it)) }

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
fun <A, B> Receiver<A>.map(transform: (A) -> B): Receiver<B> = Receiver { receive().map(transform) }

/**
 * Maps a [Receiver] of type [A] to a [Receiver] of type [B], dropping messages
 * where [transform] returns null.
 *
 * Use this when the source emits multiple message types but the consumer only
 * cares about a subset:
 *
 * ```kotlin
 * val textOnly: Receiver<String> = wsReceiver.mapNotNull { msg ->
 *     (msg as? WebSocketMessage.Text)?.value  // skip Binary, Ping, Pong, Close
 * }
 * ```
 */
fun <A, B> Receiver<A>.mapNotNull(transform: suspend (A) -> B?): Receiver<B> = Receiver { receive().mapNotNull(transform) }

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
 * Works with any [Connection] including [ReconnectingConnection] — the mapping
 * is applied per-message, so reconnection is transparent to the mapped layer.
 */
fun <A, B> Connection<A>.map(
    encode: (B) -> A,
    decode: (A) -> B,
): Connection<B> =
    object : Connection<B> {
        override val id: Long get() = this@map.id

        override suspend fun send(message: B) = this@map.send(encode(message))

        override fun receive(): Flow<B> = this@map.receive().map(decode)

        override suspend fun close() = this@map.close()
    }

/**
 * Maps a [Connection] of type [A] to a [Connection] of type [B], dropping
 * inbound messages where [decode] returns null.
 *
 * Use this when the source connection carries multiple message types (e.g.,
 * WebSocket text + binary + control frames) but the consumer only handles a
 * subset:
 *
 * ```kotlin
 * val chat: Connection<ChatMessage> = wsConn.mapNotNull(
 *     encode = { WebSocketMessage.Text(Json.encodeToString(it)) },
 *     decode = { msg ->
 *         when (msg) {
 *             is WebSocketMessage.Text -> Json.decodeFromString(msg.value)
 *             else -> null // skip control frames — no crash, no impossible state
 *         }
 *     },
 * )
 * ```
 */
fun <A, B> Connection<A>.mapNotNull(
    encode: suspend (B) -> A,
    decode: suspend (A) -> B?,
): Connection<B> =
    object : Connection<B> {
        override val id: Long get() = this@mapNotNull.id

        override suspend fun send(message: B) = this@mapNotNull.send(encode(message))

        override fun receive(): Flow<B> = this@mapNotNull.receive().mapNotNull { decode(it) }

        override suspend fun close() = this@mapNotNull.close()
    }
