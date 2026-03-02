@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

// WasmJS implementation using TextDecoder with stream mode via JS interop.
//
// Thread Safety: StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
//
// Uses the browser's native TextDecoder API with `stream: true` option.
// Two fast paths to avoid per-byte WASM→JS boundary crossings:
// - LinearBuffer: zero-copy decode via Uint8Array view on wasmExports.memory.buffer
// - Other buffers: getInt callback fills JS Uint8Array 4 bytes at a time (4x fewer crossings)

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
 * Decode bytes from WASM linear memory using a streaming decoder.
 * Zero-copy: creates a Uint8Array view directly on wasmExports.memory.buffer.
 */
@JsFun(
    """
(decoder, offset, length, stream) => {
    const bytes = new Uint8Array(wasmExports.memory.buffer, offset, length);
    return decoder.decode(bytes, { stream: stream });
}
""",
)
private external fun decodeStreamLinear(
    decoder: JsAny,
    offset: Int,
    length: Int,
    stream: JsBoolean,
): JsString

/**
 * Decode bytes using a streaming decoder with getInt callback.
 * Fills Uint8Array 4 bytes at a time — 4x fewer WASM→JS boundary crossings than getByte.
 */
@JsFun(
    """
(decoder, size, getInt, stream) => {
    const bytes = new Uint8Array(size);
    const fullInts = size >>> 2;
    for (let i = 0; i < fullInts; i++) {
        const v = getInt(i);
        const off = i << 2;
        bytes[off] = v & 0xFF;
        bytes[off + 1] = (v >>> 8) & 0xFF;
        bytes[off + 2] = (v >>> 16) & 0xFF;
        bytes[off + 3] = (v >>> 24) & 0xFF;
    }
    const tailStart = fullInts << 2;
    if (tailStart < size) {
        const v = getInt(fullInts);
        for (let j = 0; j < size - tailStart; j++) {
            bytes[tailStart + j] = (v >>> (j << 3)) & 0xFF;
        }
    }
    return decoder.decode(bytes, { stream: stream });
}
""",
)
private external fun decodeStreamBulk(
    decoder: JsAny,
    size: Int,
    getInt: (Int) -> Int,
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

private const val DECODE_CHUNK_SIZE = 32 * 1024

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

        val startPos = buffer.position()
        var totalChars = 0
        var offset = 0
        try {
            while (offset < remaining) {
                val chunkSize = minOf(DECODE_CHUNK_SIZE, remaining - offset)
                val result = decodeChunk(buffer, startPos + offset, chunkSize)
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

    private fun decodeChunk(
        buffer: ReadBuffer,
        absoluteOffset: Int,
        length: Int,
    ): String {
        val actual = buffer.unwrapFully()
        if (actual is LinearBuffer) {
            return decodeStreamLinear(
                decoder,
                actual.baseOffset + absoluteOffset,
                length,
                true.toJsBoolean(),
            ).toString()
        }

        return decodeStreamBulk(
            decoder,
            length,
            { intIndex ->
                val byteIndex = absoluteOffset + (intIndex shl 2)
                val remainingBytes = length - (intIndex shl 2)
                if (remainingBytes >= 4) {
                    (buffer.get(byteIndex).toInt() and 0xFF) or
                        ((buffer.get(byteIndex + 1).toInt() and 0xFF) shl 8) or
                        ((buffer.get(byteIndex + 2).toInt() and 0xFF) shl 16) or
                        ((buffer.get(byteIndex + 3).toInt() and 0xFF) shl 24)
                } else {
                    var v = 0
                    for (j in 0 until remainingBytes) {
                        v = v or ((buffer.get(byteIndex + j).toInt() and 0xFF) shl (j shl 3))
                    }
                    v
                }
            },
            true.toJsBoolean(),
        ).toString()
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
