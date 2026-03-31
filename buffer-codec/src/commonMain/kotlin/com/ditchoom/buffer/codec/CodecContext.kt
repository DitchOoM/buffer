package com.ditchoom.buffer.codec

/**
 * Base interface for typed key-value context that flows through codec chains.
 *
 * Keys defined on [CodecContext] are accessible in both [DecodeContext] and [EncodeContext],
 * useful for shared configuration like protocol version or byte order.
 *
 * ```kotlin
 * val VersionKey = CodecContext.Key<Int>("protocol.version")
 *
 * // Works in both directions
 * val dCtx = DecodeContext.Empty.with(VersionKey, 2)
 * val eCtx = EncodeContext.Empty.with(VersionKey, 2)
 * ```
 */
interface CodecContext {
    /**
     * Returns the value associated with [key], or null if not present.
     */
    operator fun <T : Any> get(key: Key<T>): T?

    /**
     * A typed key for storing values in a [CodecContext].
     *
     * Keys are compared by identity (reference equality). Define keys as `data object`
     * or `val` singletons on your codec object:
     *
     * ```kotlin
     * object MyCodec : Codec<Foo> {
     *     // Preferred: data object (singleton, IDE-navigable)
     *     data object AllocatorKey : CodecContext.Key<BufferAllocator>()
     *
     *     // Also valid: val with anonymous instance
     *     val MaxSizeKey = object : CodecContext.Key<Int>() {}
     * }
     * ```
     */
    abstract class Key<T : Any> {
        override fun toString(): String = this::class.simpleName ?: "CodecContext.Key"
    }
}

/**
 * Context passed to [Codec.decode] for runtime configuration during decoding.
 *
 * Use this to pass allocator hints, security policies, compression config, or any
 * other caller-supplied option through the codec chain without global state.
 *
 * ```kotlin
 * val ctx = DecodeContext.Empty
 *     .with(PngCodec.AllocatorKey, hwAllocator)
 *     .with(MaxSizeKey, 1_000_000)
 *
 * val result = MyCodec.decode(buffer, ctx)
 * ```
 */
interface DecodeContext : CodecContext {
    /**
     * Returns a new [DecodeContext] with [key] set to [value].
     * Does not modify this context — contexts are immutable.
     */
    fun <T : Any> with(
        key: CodecContext.Key<T>,
        value: T,
    ): DecodeContext

    companion object {
        /** An empty decode context with no keys set. */
        val Empty: DecodeContext = MapContext(emptyMap())
    }
}

/**
 * Context passed to [Codec.encode] for runtime configuration during encoding.
 *
 * Use this to pass compression levels, wire format versions, or other
 * caller-supplied options through the codec chain.
 *
 * ```kotlin
 * val ctx = EncodeContext.Empty
 *     .with(CompressionKey, CompressionLevel.BestSpeed)
 *
 * MyCodec.encode(buffer, value, ctx)
 * ```
 */
interface EncodeContext : CodecContext {
    /**
     * Returns a new [EncodeContext] with [key] set to [value].
     * Does not modify this context — contexts are immutable.
     */
    fun <T : Any> with(
        key: CodecContext.Key<T>,
        value: T,
    ): EncodeContext

    companion object {
        /** An empty encode context with no keys set. */
        val Empty: EncodeContext = MapContext(emptyMap())
    }
}

/**
 * Immutable map-backed implementation of both [DecodeContext] and [EncodeContext].
 * Keys are compared by identity (reference equality), not by [CodecContext.Key.name].
 */
private class MapContext(
    private val elements: Map<CodecContext.Key<*>, Any>,
) : DecodeContext,
    EncodeContext {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: CodecContext.Key<T>): T? = elements[key] as? T

    override fun <T : Any> with(
        key: CodecContext.Key<T>,
        value: T,
    ): MapContext = MapContext(elements + (key to value))

    override fun toString(): String = elements.entries.joinToString(prefix = "CodecContext(", postfix = ")") { "${it.key}=${it.value}" }
}
