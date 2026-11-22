package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Reports the encoded byte size of a value. Boxes [Exact] on every call; [WireByteCount]
 * answers the same question without allocating.
 */
@Deprecated(
    "Use WireByteCount, returned by Encoder.wireBytes.",
    ReplaceWith("WireByteCount", "com.ditchoom.buffer.codec.WireByteCount"),
)
sealed interface WireSize {
    /** Codec knows the exact byte count up front; framework pre-allocates. */
    @Suppress("DEPRECATION")
    @JvmInline
    value class Exact(
        val bytes: Int,
    ) : WireSize

    /** Framework uses a growable write buffer + back-patches length-prefixed framing. */
    @Suppress("DEPRECATION")
    data object BackPatch : WireSize
}

/**
 * Result of peeking a frame size from a [StreamProcessor]. Boxes [Complete] on every
 * call and cannot report a size whose bytes have not all arrived; [FrameSize] does both.
 */
@Deprecated(
    "Use FrameSize, returned by FrameDetector.frameSize.",
    ReplaceWith("FrameSize", "com.ditchoom.buffer.codec.FrameSize"),
)
sealed interface PeekResult {
    /** A complete frame is available; [bytes] is its total size. */
    @Suppress("DEPRECATION")
    @JvmInline
    value class Complete(
        val bytes: Int,
    ) : PeekResult

    /** More bytes are required before a frame size can be determined. */
    @Suppress("DEPRECATION")
    data object NeedsMoreData : PeekResult

    /** This codec does not participate in frame detection. */
    @Suppress("DEPRECATION")
    data object NoFraming : PeekResult
}

/** Writes [T] to a [WriteBuffer]. */
interface Encoder<in T> {
    fun encode(
        buffer: WriteBuffer,
        value: T,
        context: EncodeContext,
    )

    /**
     * The encoded size of [value], without allocating. Override this one; the default
     * asks the deprecated [wireSize], so an encoder written against it keeps working.
     */
    @Suppress("DEPRECATION")
    fun wireBytes(
        value: T,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.from(wireSize(value, context))

    /** The encoded size of [value] as a boxed [WireSize]. Defaults to [WireSize.BackPatch]. */
    @Suppress("DEPRECATION")
    @Deprecated(
        "Call wireBytes. An encoder that overrides only wireBytes answers BackPatch here; " +
            "while callers of wireSize remain, also override it as `wireBytes(value, context).toWireSize()`.",
        ReplaceWith("wireBytes(value, context)"),
    )
    fun wireSize(
        value: T,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    /**
     * Starting-capacity hint consulted by `encodeToPlatformBuffer` when
     * [wireBytes] reports [WireByteCount.BackPatch]. A cheap lower-bound guess —
     * NOT a contract: under-hinting costs grow-and-retry doublings (the
     * pre-hint behavior), over-hinting costs one-time allocation slack;
     * neither affects the encoded bytes.
     *
     * Generated codecs override this with `O(field count)` arithmetic —
     * fixed widths as constants plus `String.length` per string field
     * (exact for ASCII content, a ≥⅓ lower bound for any UTF-8) plus
     * nested/element hints. Hand-written encoders may override it whenever
     * they can guess better than [DEFAULT_SIZE_HINT].
     */
    fun sizeHint(
        value: T,
        context: EncodeContext,
    ): Int = DEFAULT_SIZE_HINT

    companion object {
        /** Fallback starting capacity when an encoder offers no better guess. */
        public const val DEFAULT_SIZE_HINT: Int = 64
    }
}

/**
 * Reads [T] synchronously from a pre-bounded [ReadBuffer].
 *
 * Codecs must not retain the buffer/slice past return. The framework releases
 * the slice on normal exit or exception via lexical `slice().use { }` /
 * `withBuffer { }` semantics. To produce a value that outlives the decode
 * scope, the codec copies bytes out at the boundary:
 *
 * - [ReadBuffer.copyToByteArray] — fresh heap `ByteArray`, safe to retain.
 * - `factory.allocate(remaining).also { it.write(buffer); it.resetForRead() }`
 *   — consumer-owned `PlatformBuffer`.
 * - `buffer.toNativeData()` → platform decoder → typed value (e.g. `Bitmap`
 *   wrapping `PlatformBitmap`) — zero-copy into a typed handle.
 *
 * See `buffer-codec/CLAUDE.md` "Canonical decode patterns" for worked
 * examples of each.
 */
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
 * `slice().use { }` / `withBuffer { }` semantics. The same consumer-boundary
 * copy primitives apply as on [Decoder.decode] — see that interface's kdoc.
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
    /**
     * The size of the frame starting [baseOffset] bytes into [stream], without consuming
     * it or allocating. Report the declared total as soon as it is known, even before the
     * frame has arrived, so a streaming loop can reject an oversized frame early.
     *
     * Override this one; the default asks the deprecated [peekFrameSize], so a detector
     * written against it keeps working. Overriding only this makes [peekFrameSize] answer
     * `NoFraming`, so also forward that while its callers remain.
     */
    @Suppress("DEPRECATION")
    fun frameSize(
        stream: StreamProcessor,
        baseOffset: Int = 0,
    ): FrameSize = FrameSize.from(peekFrameSize(stream, baseOffset))

    /** The frame size as a boxed [PeekResult]. Defaults to [PeekResult.NoFraming]. */
    @Suppress("DEPRECATION")
    @Deprecated(
        "Call frameSize. A detector that overrides only frameSize answers NoFraming here; while callers " +
            "of peekFrameSize remain, also override it as " +
            "`frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)`.",
        ReplaceWith("frameSize(stream, baseOffset)"),
    )
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
 * Marker interface for self-contained typed payload slot types.
 *
 * Generic-bounded payload slots (`<P : Payload>`) accept any type that
 * implements this interface plus `Nothing` (covariance).
 *
 * **Strict transitive shape rule** (buffer-codec lockdown v1, Change 1): the
 * KSP processor walks the declared shape of every concrete `Payload`-
 * implementing type referenced from an `@ProtocolMessage` data class and
 * rejects raw-bytes types anywhere in that shape — `ReadBuffer`,
 * `WriteBuffer`, `PlatformBuffer`, `ByteArray`, primitive arrays,
 * `java.nio.ByteBuffer`. The walk descends through value-class wrappers and
 * sealed `Payload` trees.
 *
 * Why: a `Payload` outlives the codec's decode scope, but a raw-bytes
 * member can reference reclaimed pool memory (the JS-aliased `ByteArray`
 * case) or carry implicit ownership obligations the type system can't
 * verify. Self-contained typed values close the bug class at compile time.
 *
 * Decode into a self-contained typed value:
 * - a value class around a scalar / `String` / domain object
 * - a platform-native handle (e.g. `Bitmap` over `PlatformBitmap` via
 *   `buffer.toNativeData()` → platform decoder)
 *
 * For consumers who genuinely need raw bytes (IPC forwarding, persistence,
 * debug capture), step outside the `Payload` abstraction: a non-`Payload`
 * result type decoded by a hand-written `Codec<YourType>`. Inside that
 * codec, use `ReadBuffer.copyToByteArray` for heap bytes, or
 * `factory.allocate().write(source)` for a consumer-owned `PlatformBuffer`.
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
