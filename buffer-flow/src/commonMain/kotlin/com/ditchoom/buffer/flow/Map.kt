package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

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

        // Any decorator that overrides close() MUST override abort(). Without this, abort() on the
        // wrapper resolves to Connection's default — which is close() — silently turning the
        // escape hatch for a stalled peer back into the graceful drain it exists to break out of,
        // even when the wrapped connection has a real abort.
        override suspend fun abort() = this@mapNotNull.abort()
    }
