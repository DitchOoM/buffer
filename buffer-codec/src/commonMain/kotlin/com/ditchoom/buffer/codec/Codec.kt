package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Reports the encoded byte size of a value.
 *
 * Codecs that know their size up front return [Exact] so the framework can
 * pre-allocate. Variable-length codecs (UTF-8 strings, sealed dispatch with
 * variable variants, MQTT v5 properties, generic payloads) return [BackPatch];
 * the framework writes into a growable buffer and patches any preceding
 * length-prefix on emit.
 */
sealed interface WireSize {
    /** Codec knows the exact byte count up front; framework pre-allocates. */
    @JvmInline
    value class Exact(
        val bytes: Int,
    ) : WireSize

    /** Framework uses a growable write buffer + back-patches length-prefixed framing. */
    data object BackPatch : WireSize
}

/**
 * Result of peeking a frame size from a [StreamProcessor].
 *
 * Default [NoFraming] means a codec at a framing boundary that wasn't taught
 * to peek will throw at startup instead of silently hanging the streaming loop.
 */
sealed interface PeekResult {
    /** A complete frame is available; [bytes] is its total size. */
    @JvmInline
    value class Complete(
        val bytes: Int,
    ) : PeekResult

    /** More bytes are required before a frame size can be determined. */
    data object NeedsMoreData : PeekResult

    /** This codec does not participate in frame detection. */
    data object NoFraming : PeekResult
}

/** Writes [T] to a [WriteBuffer]. */
interface Encoder<in T> {
    fun encode(
        buffer: WriteBuffer,
        value: T,
        context: EncodeContext,
    )

    /** Reports the on-wire byte size. Defaults to [WireSize.BackPatch]. */
    fun wireSize(
        value: T,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch
}

/** Reads [T] synchronously from a pre-bounded [ReadBuffer]. */
interface Decoder<out T> {
    fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T
}

/**
 * Reads [T] asynchronously from a pre-bounded [ReadBuffer] slice.
 *
 * Codecs must not retain the buffer/slice past return. The framework releases
 * the slice on normal exit, exception, or cancellation via lexical
 * `slice().use { }` / `withBuffer { }` semantics.
 */
interface SuspendingDecoder<out T> {
    suspend fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T
}

/**
 * Peeks the next frame size from a [StreamProcessor] without consuming bytes.
 * Streaming loops use this to gate calls to the synchronous [Decoder].
 */
interface FrameDetector {
    fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int = 0,
    ): PeekResult = PeekResult.NoFraming
}

/**
 * Convenience union of [Encoder], [Decoder], and [FrameDetector].
 *
 * Send-only consumers can implement just [Encoder]; receive-only consumers
 * just [Decoder] or [SuspendingDecoder]. Generated code emits whichever
 * subset the consumer's per-field path-reachability requires.
 */
interface Codec<T> :
    Encoder<T>,
    Decoder<T>,
    FrameDetector

/**
 * Empty marker interface for `@Payload` slot types.
 *
 * Generic-bounded payload slots (`<P : Payload>`) accept any type that
 * implements this interface plus `Nothing` (covariance). `ByteArray`,
 * `ReadBuffer`, `String`, etc. cannot be used as a payload type — consumers
 * wrap and copy explicitly inside their decoder lambda. See Section 8 of
 * `PHASE_10_DESIGN_PROGRESS.md` for the full rationale.
 */
interface Payload

/**
 * Open base class for decode failures. Protocol layers subclass and attach
 * domain-specific fields (e.g., `MqttReasonCode`, `WebSocketCloseCode`).
 */
open class DecodeException(
    val fieldPath: String,
    val bufferPosition: Int,
    val expected: String,
    val actual: String,
    cause: Throwable? = null,
) : IllegalStateException(
        "Decode failed at $fieldPath (offset=$bufferPosition): expected $expected, got $actual",
        cause,
    )

/**
 * Open base class for encode failures. Protocol layers subclass and attach
 * domain-specific fields.
 */
open class EncodeException(
    val fieldPath: String,
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException("Encode failed at $fieldPath: $reason", cause)
