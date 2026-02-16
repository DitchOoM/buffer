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

        // Get data as Uint8Array
        val uint8Array = getUint8Array(buffer, remaining)
        buffer.position(buffer.position() + remaining)

        return try {
            val result: String = decoder.decode(uint8Array, js("{ stream: true }"))
            destination.append(result)
            result.length
        } catch (e: Throwable) {
            handleError(destination, e)
        }
    }

    private fun getUint8Array(
        buffer: ReadBuffer,
        remaining: Int,
    ): Uint8Array {
        // Try to get direct access to underlying Int8Array (unwrap PooledBuffer if needed)
        val actual = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
        if (actual is JsBuffer) {
            val int8Array = actual.buffer
            // Create a view of just the remaining bytes
            return Uint8Array(int8Array.buffer, int8Array.byteOffset + buffer.position(), remaining)
        }

        // Fallback: copy bytes
        val bytes = ByteArray(remaining)
        val startPos = buffer.position()
        for (i in 0 until remaining) {
            bytes[i] = buffer.get(startPos + i)
        }
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

    override suspend fun close() {
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
