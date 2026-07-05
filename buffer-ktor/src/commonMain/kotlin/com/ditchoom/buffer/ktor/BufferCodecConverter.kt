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
 * @param codec the message codec.
 * @param factory allocator for decode/encode buffers (default [BufferFactory.Default]).
 * @param encodeContext context threaded to the encoder (default [EncodeContext.Empty]).
 * @param decodeContext context threaded to the decoder (default [DecodeContext.Empty]).
 */
public class BufferCodecConverter<T : Any>(
    private val codec: Codec<T>,
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
        if (value == null) return null

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
        return codec.decode(buffer, decodeContext)
    }

    private class BufferCodecContent(
        private val payload: ByteArray,
        override val contentType: ContentType,
    ) : OutgoingContent.ByteArrayContent() {
        override val contentLength: Long get() = payload.size.toLong()

        override fun bytes(): ByteArray = payload
    }
}
