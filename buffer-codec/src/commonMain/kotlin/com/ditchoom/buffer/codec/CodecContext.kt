package com.ditchoom.buffer.codec

/**
 * Marker for typed key-value context that flows through codec chains.
 *
 * Direction is encoded in the key: [DecodeKey] keys are visible only to
 * [DecodeContext], [EncodeKey] keys only to [EncodeContext]. A [CodecKey]
 * extends both for legitimate bidirectional configuration. Keys are
 * compared by identity and should be Kotlin `object`s — KSP enforces
 * object-only implementations once that validation pass lands.
 */
interface CodecContext

/** Key visible only to [DecodeContext]. Define as a Kotlin `object`. */
interface DecodeKey<T : Any>

/** Key visible only to [EncodeContext]. Define as a Kotlin `object`. */
interface EncodeKey<T : Any>

/** Key visible to both directions. Define as a Kotlin `object`. */
interface CodecKey<T : Any> :
    DecodeKey<T>,
    EncodeKey<T>

/**
 * Context passed to [Decoder.decode] / [SuspendingDecoder.decode] for
 * runtime configuration (allocator hints, max-size guards, version pins,
 * etc.). Contexts are immutable; [with] returns a new context.
 */
interface DecodeContext : CodecContext {
    operator fun <T : Any> get(key: DecodeKey<T>): T?

    fun <T : Any> with(
        key: DecodeKey<T>,
        value: T,
    ): DecodeContext

    companion object {
        val Empty: DecodeContext = DecodeMapContext(emptyMap())
    }
}

/**
 * Context passed to [Encoder.encode] for runtime configuration.
 * Contexts are immutable; [with] returns a new context.
 */
interface EncodeContext : CodecContext {
    operator fun <T : Any> get(key: EncodeKey<T>): T?

    fun <T : Any> with(
        key: EncodeKey<T>,
        value: T,
    ): EncodeContext

    companion object {
        val Empty: EncodeContext = EncodeMapContext(emptyMap())
    }
}

private class DecodeMapContext(
    private val elements: Map<DecodeKey<*>, Any>,
) : DecodeContext {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: DecodeKey<T>): T? = elements[key] as? T

    override fun <T : Any> with(
        key: DecodeKey<T>,
        value: T,
    ): DecodeContext = DecodeMapContext(elements + (key to value))

    override fun toString(): String = elements.entries.joinToString(prefix = "DecodeContext(", postfix = ")") { "${it.key}=${it.value}" }
}

private class EncodeMapContext(
    private val elements: Map<EncodeKey<*>, Any>,
) : EncodeContext {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: EncodeKey<T>): T? = elements[key] as? T

    override fun <T : Any> with(
        key: EncodeKey<T>,
        value: T,
    ): EncodeContext = EncodeMapContext(elements + (key to value))

    override fun toString(): String = elements.entries.joinToString(prefix = "EncodeContext(", postfix = ")") { "${it.key}=${it.value}" }
}
