package com.ditchoom.buffer

/**
 * Stateful string decoder optimized for streaming scenarios.
 *
 * This decoder is designed for high-performance decoding of chunked byte streams
 * (e.g., from compression, network I/O) directly to an [Appendable] without
 * intermediate String allocations.
 *
 * ## Key Features
 *
 * - **Zero intermediate allocations**: Decodes directly to destination
 * - **Automatic boundary handling**: Manages incomplete multi-byte sequences across chunks
 * - **Reusable**: Create once, use for multiple streams via [reset]
 * - **Platform-optimized**: Uses native APIs where available (JVM CharsetDecoder, etc.)
 *
 * ## Usage
 *
 * ```kotlin
 * val decoder = StreamingStringDecoder()
 * val result = StringBuilder()
 *
 * // Process chunks as they arrive
 * chunks.forEach { chunk ->
 *     decoder.decode(chunk, result)
 * }
 *
 * // Finalize and get result
 * decoder.finish(result)
 * val text = result.toString()
 *
 * // Reuse for next stream
 * decoder.reset()
 * ```
 *
 * ## Thread Safety
 *
 * StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
 *
 * @see StreamingStringDecoderConfig for configuration options
 */
interface StreamingStringDecoder : SuspendCloseable {
    /**
     * Decodes bytes from buffer and appends characters to destination.
     *
     * This method handles incomplete multi-byte sequences automatically:
     * - If a chunk ends mid-sequence, the partial bytes are saved internally
     * - On the next [decode] call, the sequence is completed
     *
     * The buffer's position is advanced by the number of bytes consumed.
     * Bytes that are part of an incomplete trailing sequence are NOT consumed
     * (they remain in the buffer for the next call, or are saved internally
     * depending on implementation).
     *
     * @param buffer Source buffer containing encoded bytes
     * @param destination Where to append decoded characters
     * @return Number of characters appended to destination
     * @throws CharacterDecodingException if malformed input is encountered
     *         and [StreamingStringDecoderConfig.onMalformedInput] is [DecoderErrorAction.REPORT]
     */
    fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int

    /**
     * Flushes any pending incomplete sequence at end of stream.
     *
     * Call this after processing the final chunk to handle any trailing
     * bytes that form an incomplete multi-byte sequence.
     *
     * Behavior depends on [StreamingStringDecoderConfig.onMalformedInput]:
     * - [DecoderErrorAction.REPORT]: Throws if incomplete sequence remains
     * - [DecoderErrorAction.REPLACE]: Appends replacement character (U+FFFD)
     *
     * @param destination Where to append any final characters
     * @return Number of characters appended (0 or 1 typically)
     */
    fun finish(destination: Appendable): Int

    /**
     * Resets decoder state for reuse with a new stream.
     *
     * Clears any pending incomplete sequences and resets internal state.
     * After calling reset(), the decoder can be used for a completely new
     * byte stream.
     */
    fun reset()
}

/**
 * Configuration for [StreamingStringDecoder] creation.
 *
 * @property charset Character encoding to decode. Default: UTF-8
 * @property charBufferSize Size of internal character buffer for platforms that use one (JVM).
 *           Larger values reduce decode() calls but use more memory. Default: 8192
 * @property onMalformedInput How to handle malformed input sequences. Default: REPORT (throw)
 * @property onUnmappableCharacter How to handle unmappable characters. Default: REPORT (throw)
 */
data class StreamingStringDecoderConfig(
    val charset: Charset = Charset.UTF8,
    val charBufferSize: Int = 8192,
    val onMalformedInput: DecoderErrorAction = DecoderErrorAction.REPORT,
    val onUnmappableCharacter: DecoderErrorAction = DecoderErrorAction.REPORT,
) {
    init {
        require(charBufferSize > 0) { "charBufferSize must be positive, got: $charBufferSize" }
    }

    companion object {
        /** Default configuration: UTF-8, 8KB buffer, strict error handling */
        val DEFAULT = StreamingStringDecoderConfig()

        /** Configuration for lenient decoding that replaces errors with U+FFFD */
        val LENIENT =
            StreamingStringDecoderConfig(
                onMalformedInput = DecoderErrorAction.REPLACE,
                onUnmappableCharacter = DecoderErrorAction.REPLACE,
            )
    }
}

/**
 * Action to take when the decoder encounters an error.
 */
enum class DecoderErrorAction {
    /**
     * Report the error by throwing an exception.
     * This is the strictest option and ensures data integrity.
     */
    REPORT,

    /**
     * Replace the malformed/unmappable input with a replacement character (U+FFFD).
     * Useful when you want to process data even if it contains errors.
     */
    REPLACE,
}

/**
 * Exception thrown when character decoding fails.
 */
class CharacterDecodingException(
    message: String,
    val inputPosition: Int = -1,
) : Exception(message)

/**
 * Creates a platform-optimized [StreamingStringDecoder].
 *
 * Platform implementations:
 * - **JVM/Android**: Uses [java.nio.charset.CharsetDecoder] with reusable CharBuffer
 * - **Apple (iOS/macOS)**: Uses Core Foundation string APIs
 * - **Linux**: Inline UTF-8 state machine with direct native memory access
 * - **JS**: Uses TextDecoder API
 *
 * @param config Decoder configuration. Default: [StreamingStringDecoderConfig.DEFAULT]
 * @return A new StreamingStringDecoder instance
 */
expect fun StreamingStringDecoder(config: StreamingStringDecoderConfig = StreamingStringDecoderConfig.DEFAULT): StreamingStringDecoder
