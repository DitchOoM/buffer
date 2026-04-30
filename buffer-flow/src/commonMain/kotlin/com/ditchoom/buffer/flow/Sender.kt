package com.ditchoom.buffer.flow

/**
 * Sends typed messages. Used for unidirectional outbound streams.
 *
 * `fun interface` enables SAM lambda for simple cases:
 * ```kotlin
 * val sender = Sender<String> { msg -> channel.send(msg) }
 * ```
 */
fun interface Sender<in T> {
    suspend fun send(message: T)
}
