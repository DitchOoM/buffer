@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

// WasmJS implementation using TextDecoder with stream mode via JS interop.
//
// Thread Safety: StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
//
// Uses the browser's native TextDecoder API with `stream: true` option.
// - LinearBuffer: zero-copy decode via Uint8Array view on wasmExports.memory.buffer
// - Other buffers: bulk copy to linear memory scratch space, then decode (single JS call)

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

    /** Lazy scratch region in linear memory for non-LinearBuffer decoding. */
    private var scratchOffset: Int = -1

    private fun ensureScratch(): Int {
        if (scratchOffset == -1) {
            scratchOffset = LinearMemoryAllocator.allocateOffset(DECODE_CHUNK_SIZE)
        }
        return scratchOffset
    }

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

        // Single bulk copy: backing array → linear memory scratch, then decode.
        // ManagedMemoryAccess gives direct access to ByteArrayBuffer.data (no copy).
        // copyMemoryFromArray uses WASM i32 stores (no JS boundary crossings).
        val managed = actual.managedMemoryAccess
        if (managed != null) {
            val scratch = ensureScratch()
            UnsafeMemory.copyMemoryFromArray(
                managed.backingArray,
                managed.arrayOffset + absoluteOffset,
                scratch.toLong(),
                length,
            )
            return decodeStreamLinear(decoder, scratch, length, true.toJsBoolean()).toString()
        }

        // Final fallback for unknown buffer types
        val savedPos = buffer.position()
        buffer.position(absoluteOffset)
        val bytes = buffer.readByteArray(length)
        buffer.position(savedPos)
        val scratch = ensureScratch()
        UnsafeMemory.copyMemoryFromArray(bytes, 0, scratch.toLong(), length)
        return decodeStreamLinear(decoder, scratch, length, true.toJsBoolean()).toString()
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
