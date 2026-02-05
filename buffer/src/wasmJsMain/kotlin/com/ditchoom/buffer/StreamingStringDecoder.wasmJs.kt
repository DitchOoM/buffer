@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

// WasmJS implementation using TextDecoder with stream mode via JS interop.
//
// Thread Safety: StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
//
// Uses the browser's native TextDecoder API with `stream: true` option.
// In Kotlin/WASM, ByteArray lives in WasmGC heap (separate from linear memory),
// so we use a callback-based approach to pass bytes to JS.

/** Create a streaming TextDecoder instance. Returns an opaque handle to the decoder. */
@JsFun(
    """
(encoding, fatal) => {
    return new TextDecoder(encoding, { fatal: fatal });
}
""",
)
private external fun createDecoder(
    encoding: JsString,
    fatal: JsBoolean,
): JsAny

/**
 * Decode bytes using a streaming decoder.
 * Returns the decoded string.
 */
@JsFun(
    """
(decoder, size, getByte, stream) => {
    const bytes = new Uint8Array(size);
    for (let i = 0; i < size; i++) {
        bytes[i] = getByte(i);
    }
    return decoder.decode(bytes, { stream: stream });
}
""",
)
private external fun decodeStream(
    decoder: JsAny,
    size: Int,
    getByte: (Int) -> Byte,
    stream: JsBoolean,
): JsString

/**
 * Finish decoding and flush any pending bytes.
 */
@JsFun(
    """
(decoder) => {
    return decoder.decode(new Uint8Array(0), { stream: false });
}
""",
)
private external fun finishDecode(decoder: JsAny): JsString

private class WasmJsStreamingStringDecoder(
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
            else -> throw UnsupportedOperationException("Charset ${config.charset} not supported in WasmJS")
        }

    private val fatal = config.onMalformedInput == DecoderErrorAction.REPORT
    private val decoder = createDecoder(encoding.toJsString(), fatal.toJsBoolean())

    override fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int {
        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        // Read bytes with indexed access
        val startPos = buffer.position()

        return try {
            val result =
                decodeStream(
                    decoder,
                    remaining,
                    { i -> buffer.get(startPos + i) },
                    true.toJsBoolean(),
                ).toString()

            buffer.position(startPos + remaining)
            destination.append(result)
            result.length
        } catch (e: Throwable) {
            buffer.position(startPos + remaining)
            handleError(destination, e)
        }
    }

    override fun finish(destination: Appendable): Int =
        try {
            val result = finishDecode(decoder).toString()
            if (result.isNotEmpty()) {
                destination.append(result)
            }
            result.length
        } catch (e: Throwable) {
            handleError(destination, e)
        }

    override fun reset() {
        // Flush the decoder to reset its state
        try {
            finishDecode(decoder)
        } catch (_: Throwable) {
            // Ignore errors during reset
        }
    }

    override suspend fun close() {
        // Nothing to close - decoder is GC'd
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

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = WasmJsStreamingStringDecoder(config)
