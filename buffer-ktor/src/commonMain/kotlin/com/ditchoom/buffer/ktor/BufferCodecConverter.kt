package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import kotlin.reflect.KClass

/**
 * A Ktor [ContentConverter] that serializes and deserializes a single [Codec]-backed message type
 * over a binary content type.
 *
 * Bind one converter per message type at plugin installation:
 * ```kotlin
 * install(ContentNegotiation) {
 *     register(ContentType.Application.OctetStream, BufferCodecConverter(MyMessageCodec))
 * }
 * ```
 *
 * The codec and [BufferFactory] are supplied at construction so the caller controls the wire
 * format and allocation strategy. [encodeContext] / [decodeContext] thread typed configuration
 * (e.g. [com.ditchoom.buffer.codec.BufferFactoryKey], size guards) into the codec.
 *
 * Copy semantics throughout: request bodies are copied out of the Ktor channel into a
 * factory-allocated [com.ditchoom.buffer.PlatformBuffer] before decode; responses are encoded into
 * a fresh buffer and copied into the outgoing body.
 *
 * **Codec ownership contract:** [deserialize] frees its input buffer as soon as [Codec.decode]
 * returns — including when decode throws — so a codec bound to this converter must return a value
 * that *owns* its data rather than aliasing the input buffer. Follow one of the canonical decode
 * patterns (see `buffer-codec`'s "Canonical decode patterns"): decode straight into a typed value
 * (e.g. via `toNativeData()`), copy into a consumer-owned `PlatformBuffer` via `write(source)`, or
 * copy into a heap `ByteArray` via `copyToByteArray(n)`. A codec that instead returns a `slice()` /
 * `readBytes(n)` view of the input buffer will observe freed or reused memory once [deserialize]
 * returns.
 *
 * [T] is bound to a single [KClass] at construction; use the reified `BufferCodecConverter(codec)`
 * factory below rather than this primary constructor unless you already have the [KClass] in hand.
 *
 * @param codec the message codec.
 * @param type the runtime class this converter accepts. [serialize] returns `null` (falling
 *   through to the next registered converter) for any other requested [TypeInfo].
 * @param factory allocator for decode/encode buffers (default [BufferFactory.Default]).
 * @param encodeContext context threaded to the encoder (default [EncodeContext.Empty]).
 * @param decodeContext context threaded to the decoder (default [DecodeContext.Empty]).
 */
public class BufferCodecConverter<T : Any>(
    private val codec: Codec<T>,
    private val type: KClass<T>,
    private val factory: BufferFactory = BufferFactory.Default,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    private val decodeContext: DecodeContext = DecodeContext.Empty,
) : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        if (value == null || typeInfo.type != type) return null

        @Suppress("UNCHECKED_CAST")
        val typed = value as T
        val encoded = codec.encodeToPlatformBuffer(typed, factory, encodeContext)
        val bytes = encoded.copyToByteArray(encoded.remaining())
        encoded.freeNativeMemory()
        return BufferCodecContent(bytes, contentType)
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any {
        val buffer = content.readRemainingBuffer(factory)
        try {
            return codec.decode(buffer, decodeContext)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    private class BufferCodecContent(
        private val payload: ByteArray,
        override val contentType: ContentType,
    ) : OutgoingContent.ByteArrayContent() {
        override val contentLength: Long get() = payload.size.toLong()

        override fun bytes(): ByteArray = payload
    }

    public companion object {
        /**
         * Creates a [BufferCodecConverter] bound to the reified type [T], inferring its [KClass]
         * so call sites don't need to pass one explicitly:
         * ```kotlin
         * BufferCodecConverter(MyMessageCodec)
         * ```
         */
        public inline operator fun <reified T : Any> invoke(
            codec: Codec<T>,
            factory: BufferFactory = BufferFactory.Default,
            encodeContext: EncodeContext = EncodeContext.Empty,
            decodeContext: DecodeContext = DecodeContext.Empty,
        ): BufferCodecConverter<T> = BufferCodecConverter(codec, T::class, factory, encodeContext, decodeContext)
    }
}
