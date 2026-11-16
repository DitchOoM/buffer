package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [FrameDetector.peekFrame] learned about the next frame.
 *
 * The allocation-free sibling of [PeekResult]. [PeekResult] is a sealed
 * interface, so a `Complete` returned through it is boxed on every peek;
 * `FramePeek` is a value class over a single `Long` that is returned
 * directly, never through an interface, so a peek costs no allocation.
 *
 * Inspect it through [kind] and [size], which do not box either:
 *
 * ```kotlin
 * val peek = codec.peekFrame(stream)
 * when (peek.kind) {
 *     FramePeekKind.Complete -> decode(peek.size)
 *     FramePeekKind.DeclaredIncomplete -> checkCeiling(peek.size) // size known, bytes not all here
 *     FramePeekKind.NeedsMoreData -> readMore()
 *     FramePeekKind.NoFraming -> error("not self-delimiting")
 * }
 * ```
 *
 * Unlike [PeekResult], a codec can report a frame size it has computed but
 * whose bytes have not all arrived ([declaredIncomplete]), which lets a
 * streaming loop reject an oversized declaration before buffering it.
 */
@JvmInline
public value class FramePeek private constructor(
    private val bits: Long,
) {
    /** Which of the four states this peek is in. */
    public val kind: FramePeekKind
        get() =
            when ((bits ushr KIND_SHIFT).toInt()) {
                KIND_NEEDS_MORE_DATA -> FramePeekKind.NeedsMoreData
                KIND_NO_FRAMING -> FramePeekKind.NoFraming
                KIND_COMPLETE -> FramePeekKind.Complete
                else -> FramePeekKind.DeclaredIncomplete
            }

    /**
     * The frame's total byte size, for [FramePeekKind.Complete] and
     * [FramePeekKind.DeclaredIncomplete]. The value is whatever the codec
     * computed; it may be zero or negative if the wire declared nonsense, so
     * callers bound it (see `readFrame`).
     *
     * @throws IllegalStateException for [FramePeekKind.NeedsMoreData] and
     *   [FramePeekKind.NoFraming], which carry no size.
     */
    public val size: Int
        get() {
            check(hasSize) { "A $kind peek carries no frame size" }
            return bits.toInt()
        }

    /** A complete frame is buffered; [size] is its total byte size. */
    public val isComplete: Boolean get() = (bits ushr KIND_SHIFT).toInt() == KIND_COMPLETE

    /** The size is known ([size]) but the frame's bytes have not all arrived. */
    public val isDeclaredIncomplete: Boolean get() = (bits ushr KIND_SHIFT).toInt() == KIND_DECLARED_INCOMPLETE

    /** More bytes are needed before a frame size can be determined. */
    public val isNeedsMoreData: Boolean get() = (bits ushr KIND_SHIFT).toInt() == KIND_NEEDS_MORE_DATA

    /** This codec does not participate in frame detection. */
    public val isNoFraming: Boolean get() = (bits ushr KIND_SHIFT).toInt() == KIND_NO_FRAMING

    private val hasSize: Boolean get() = isComplete || isDeclaredIncomplete

    /**
     * This peek shifted by [prefixBytes] that precede the peeked frame: a sized
     * state ([FramePeekKind.Complete], [FramePeekKind.DeclaredIncomplete]) gets
     * [prefixBytes] added to its [size]; the others are returned unchanged.
     */
    public fun withPrefix(prefixBytes: Int): FramePeek =
        when (kind) {
            FramePeekKind.Complete -> complete(prefixBytes + size)
            FramePeekKind.DeclaredIncomplete -> declaredIncomplete(prefixBytes + size)
            FramePeekKind.NeedsMoreData, FramePeekKind.NoFraming -> this
        }

    /**
     * For an enclosing frame that passes a nested peek's incomplete answer up:
     * a nested [FramePeekKind.DeclaredIncomplete] size is not the enclosing
     * frame's size, so it becomes [FramePeekKind.NeedsMoreData]. Other states
     * are returned unchanged.
     */
    public fun withoutDeclaredSize(): FramePeek = if (isDeclaredIncomplete) needsMoreData else this

    /**
     * The same answer as a [PeekResult], for code written against that type.
     * Allocates for [FramePeekKind.Complete]. [FramePeekKind.DeclaredIncomplete]
     * maps to [PeekResult.NeedsMoreData], the only incomplete state it has.
     */
    public fun toPeekResult(): PeekResult =
        when (kind) {
            FramePeekKind.Complete -> PeekResult.Complete(size)
            FramePeekKind.DeclaredIncomplete, FramePeekKind.NeedsMoreData -> PeekResult.NeedsMoreData
            FramePeekKind.NoFraming -> PeekResult.NoFraming
        }

    override fun toString(): String =
        when (kind) {
            FramePeekKind.Complete -> "FramePeek.Complete($size)"
            FramePeekKind.DeclaredIncomplete -> "FramePeek.DeclaredIncomplete($size)"
            FramePeekKind.NeedsMoreData -> "FramePeek.NeedsMoreData"
            FramePeekKind.NoFraming -> "FramePeek.NoFraming"
        }

    public companion object {
        private const val KIND_SHIFT = 32
        private const val SIZE_MASK = 0xFFFF_FFFFL
        private const val KIND_NEEDS_MORE_DATA = 0
        private const val KIND_NO_FRAMING = 1
        private const val KIND_COMPLETE = 2
        private const val KIND_DECLARED_INCOMPLETE = 3

        private fun of(
            kind: Int,
            size: Int,
        ): FramePeek = FramePeek((kind.toLong() shl KIND_SHIFT) or (size.toLong() and SIZE_MASK))

        /** More bytes are needed before a frame size can be determined. */
        public val needsMoreData: FramePeek get() = of(KIND_NEEDS_MORE_DATA, 0)

        /** This codec does not participate in frame detection. */
        public val noFraming: FramePeek get() = of(KIND_NO_FRAMING, 0)

        /** A complete frame of [size] total bytes is buffered. */
        public fun complete(size: Int): FramePeek = of(KIND_COMPLETE, size)

        /**
         * The codec computed the frame's total size, [declaredSize], but the
         * frame's bytes have not all arrived. Lets a streaming loop reject an
         * oversized declaration without waiting for it to finish (issue #308).
         */
        public fun declaredIncomplete(declaredSize: Int): FramePeek = of(KIND_DECLARED_INCOMPLETE, declaredSize)

        /**
         * [complete] when [available] covers [declaredSize], otherwise
         * [declaredIncomplete]. The shape every length-driven peek ends in.
         */
        public fun forDeclaredSize(
            declaredSize: Int,
            available: Int,
        ): FramePeek = if (available >= declaredSize) complete(declaredSize) else declaredIncomplete(declaredSize)

        /** Converts a [PeekResult]; the default [FrameDetector.peekFrame] uses this. */
        public fun from(result: PeekResult): FramePeek =
            when (result) {
                is PeekResult.Complete -> complete(result.bytes)
                PeekResult.NeedsMoreData -> needsMoreData
                PeekResult.NoFraming -> noFraming
            }
    }
}

/** The state of a [FramePeek]; an enum, so `when (peek.kind)` is exhaustive and allocation-free. */
public enum class FramePeekKind {
    /** A complete frame is buffered. */
    Complete,

    /** The frame size is known but its bytes have not all arrived. */
    DeclaredIncomplete,

    /** More bytes are needed before a frame size can be determined. */
    NeedsMoreData,

    /** The codec does not participate in frame detection. */
    NoFraming,
}
