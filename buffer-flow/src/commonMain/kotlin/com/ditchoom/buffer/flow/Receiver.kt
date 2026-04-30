package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow

/**
 * Receives typed messages as a Flow. Used for unidirectional inbound streams.
 *
 * `fun interface` enables SAM lambda for simple cases:
 * ```kotlin
 * val receiver = Receiver<String> { flowOf("hello", "world") }
 * ```
 */
fun interface Receiver<out T> {
    fun receive(): Flow<T>
}
