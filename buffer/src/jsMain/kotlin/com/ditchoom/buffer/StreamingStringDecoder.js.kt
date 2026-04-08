package com.ditchoom.buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * JavaScript implementation using TextDecoder with stream mode.
 *
 * ## Thread Safety
 *
 * StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
 *
 * ## Implementation
 *
 * Uses the browser's native TextDecoder API with `stream: true` option,
 * which automatically handles incomplete multi-byte sequences across chunks.
 */
private const val DECODE_CHUNK_SIZE = 32 * 1024

private class JsStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    private val encoding =
        when (config.charset) {
            Charset.UTF8 -> "utf-8"
            Charset.UTF16 -> "utf-16"
            Charset.UTF16BigEndian -> "utf-16be"
            Charset.UTF16LittleEndian -> "utf-16le"
            Charset.ASCII -> "ascii"
            Charset.ISOLatin1 -> "iso-8859-1"
            else -> throw UnsupportedOperationException("Charset ${config.charset} not supported in JS")
        }

    private val fatal = config.onMalformedInput == DecoderErrorAction.REPORT

    private val decoder: dynamic = createTextDecoder(encoding, fatal)

    private fun createTextDecoder(
        encoding: String,
        fatal: Boolean,
    ): dynamic = js("new TextDecoder(encoding, { fatal: fatal })")

    override fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int {
        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        val startPos = buffer.position()
        var totalChars = 0
        try {
            var offset = 0
            while (offset < remaining) {
                val chunkSize = minOf(DECODE_CHUNK_SIZE, remaining - offset)
                val uint8Array = getUint8ArrayChunk(buffer, startPos + offset, chunkSize)
                val result: String = decoder.decode(uint8Array, js("{ stream: true }"))
                destination.append(result)
                totalChars += result.length
                offset += chunkSize
            }
        } catch (e: Throwable) {
            totalChars += handleError(destination, e)
        } finally {
            buffer.position(startPos + remaining)
        }
        return totalChars
    }

    private fun getUint8ArrayChunk(
        buffer: ReadBuffer,
        offset: Int,
        length: Int,
    ): Uint8Array {
        val actual = buffer.unwrapFully()
        if (actual is JsBuffer) {
            val int8Array = actual.buffer
            val view = Uint8Array(int8Array.buffer, int8Array.byteOffset + offset, length)
            // TextDecoder.decode() rejects SharedArrayBuffer-backed views in Chrome.
            // Copy to a regular ArrayBuffer when the backing store is shared.
            if (actual.sharedArrayBuffer != null) {
                return Uint8Array(length).also { it.set(view) }
            }
            return view
        }

        // Fallback: bulk copy via readByteArray (uses copyOfRange on ByteArrayBuffer)
        val savedPos = buffer.position()
        buffer.position(offset)
        val bytes = buffer.readByteArray(length)
        buffer.position(savedPos)
        return Uint8Array(bytes.unsafeCast<Int8Array>().buffer)
    }

    override fun finish(destination: Appendable): Int =
        try {
            // Call decode with stream: false to flush any pending bytes
            val result: String = decoder.decode(js("undefined"), js("{ stream: false }"))
            if (result.isNotEmpty()) {
                destination.append(result)
            }
            result.length
        } catch (e: Throwable) {
            handleError(destination, e)
        }

    override fun reset() {
        // TextDecoder doesn't have a reset method, but calling decode with stream: false
        // and undefined input effectively resets it
        try {
            decoder.decode(js("undefined"), js("{ stream: false }"))
        } catch (_: Throwable) {
            // Ignore errors during reset
        }
    }

    override fun close() {
        // Nothing to close
    }

    private fun handleError(
        destination: Appendable,
        e: Throwable,
    ): Int =
        when (config.onMalformedInput) {
            DecoderErrorAction.REPORT -> throw CharacterDecodingException(e.message ?: "Decoding error")
            DecoderErrorAction.REPLACE -> {
                destination.append('\uFFFD')
                1
            }
        }
}

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = JsStreamingStringDecoder(config)
