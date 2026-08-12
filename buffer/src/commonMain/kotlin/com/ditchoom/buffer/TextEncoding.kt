package com.ditchoom.buffer

/**
 * A text-encode policy for [WriteBuffer.writeText]: a charset plus a malformed-input policy.
 *
 * The type parameter [R] is the write-result type, chosen by the policy — so a lenient write
 * cannot express failure and a strict result cannot be ignored without the compiler seeing it:
 *
 * ```kotlin
 * buffer.writeText(text, Utf8.Lenient)                 // returns WriteBuffer — fluent, cannot fail
 * when (val r = buffer.writeText(text, Utf8.Strict)) { // returns TextOutcome — must be handled
 *     is TextOutcome.Bytes -> send(r.count)
 *     is TextOutcome.Malformed -> reject(r.index)
 * }
 * ```
 *
 * **The invariant every platform upholds:** the policy's `size(text)` reports exactly the bytes
 * `writeText(text, policy)` writes. Sizing from the same policy you write with makes buffer
 * overflow unreachable and under-counting unrepresentable.
 *
 * The hierarchy is sealed: every policy that exists is one every platform implements and tests.
 * Third-party [WriteBuffer] implementations inherit the common default of [WriteBuffer.writeText]
 * (which routes through [WriteBuffer.writeBytes]) and may decorate it via a `super` call, but
 * cannot re-implement the policy mapping — that stays inside the library on purpose.
 */
sealed class TextEncoding<out R> {
    /** Maps a successful write of [byteCount] bytes into the policy's result type. */
    internal abstract fun written(
        buffer: WriteBuffer,
        byteCount: Int,
    ): R

    /** Maps a rejected write (first ill-formed code unit at [index]) into the policy's result type. */
    internal abstract fun malformed(index: Int): R

    /**
     * Re-binds a result produced against a wrapped buffer to the wrapper [self].
     * Wrappers (PooledBuffer, TrackedSlice) delegate `writeText` to their inner buffer and pass
     * the result through this, so fluent policies return the wrapper — never the unwrapped buffer.
     */
    internal open fun rewrap(
        result: @UnsafeVariance R,
        self: WriteBuffer,
    ): R = result
}

/** UTF-8 [TextEncoding] policies. */
object Utf8 {
    /**
     * Substituting UTF-8: each unpaired surrogate is written as U+FFFD (three bytes).
     * Cannot fail, so the write is fluent and [size] is a plain [Int].
     * Produces identical bytes on every platform.
     */
    object Lenient : TextEncoding<WriteBuffer>() {
        /** Exactly the bytes `writeText(text, Lenient)` writes — on every platform. */
        fun size(text: CharSequence): Int = Utf8TextEncoder.sizeSubstituting(text)

        override fun written(
            buffer: WriteBuffer,
            byteCount: Int,
        ): WriteBuffer = buffer

        override fun malformed(index: Int): Nothing = error("unreachable: Lenient substitutes instead of rejecting")

        override fun rewrap(
            result: WriteBuffer,
            self: WriteBuffer,
        ): WriteBuffer = self
    }

    /**
     * Validating UTF-8: ill-formed UTF-16 input rejects the whole write —
     * **atomically, with the buffer position unchanged** — as [TextOutcome.Malformed].
     * Well-formed input writes the same bytes as [Lenient].
     */
    object Strict : TextEncoding<TextOutcome>() {
        /**
         * [TextOutcome.Bytes] with exactly the bytes `writeText(text, Strict)` writes, or
         * [TextOutcome.Malformed] with the index of the first unpaired surrogate.
         */
        fun size(text: CharSequence): TextOutcome {
            val bad = Utf8TextEncoder.firstMalformedIndex(text)
            return if (bad >= 0) {
                TextOutcome.Malformed(bad)
            } else {
                TextOutcome.Bytes(Utf8TextEncoder.sizeSubstituting(text))
            }
        }

        override fun written(
            buffer: WriteBuffer,
            byteCount: Int,
        ): TextOutcome = TextOutcome.Bytes(byteCount)

        override fun malformed(index: Int): TextOutcome = TextOutcome.Malformed(index)
    }
}

/** Result of a strict text operation: the byte count, or where the input is ill-formed. */
sealed interface TextOutcome {
    /** The operation covers exactly [count] bytes. */
    data class Bytes(
        val count: Int,
    ) : TextOutcome

    /** The input is ill-formed UTF-16; [index] is the first unpaired surrogate. Nothing was written. */
    data class Malformed(
        val index: Int,
    ) : TextOutcome
}

/** Allocation sizing strategy for [toReadBuffer]-style operations that allocate before writing. */
sealed interface SizeHint {
    /** One measuring pass over the text, then allocate precisely. Minimal memory, two passes. */
    data object Exact : SizeHint

    /** No measuring pass; allocate the worst case for the policy's charset. Fast, up to 3x memory. */
    data object UpperBound : SizeHint

    /**
     * Caller-tuned ratio for corpora with a known profile (e.g. `1.1f` for ASCII-heavy text).
     * If the guess is too small the write throws [BufferOverflowException] — size from
     * [Exact]/[UpperBound] when the input is not under your control.
     */
    data class BytesPerChar(
        val ratio: Float,
    ) : SizeHint {
        init {
            require(ratio in MIN_RATIO..MAX_RATIO) { "ratio must be in [$MIN_RATIO, $MAX_RATIO]" }
        }

        private companion object {
            const val MIN_RATIO = 1f
            const val MAX_RATIO = 4f
        }
    }
}

/** Writes [text] as substituting UTF-8 — the drop-in replacement for `writeString(text)`. */
fun WriteBuffer.writeText(text: CharSequence): WriteBuffer = writeText(text, Utf8.Lenient)

/** Exactly the bytes a lenient UTF-8 write of this text produces, on every platform. */
fun CharSequence.utf8Size(): Int = Utf8.Lenient.size(this)

/** UTF-8 bytes-per-char upper bound under substitution (a surrogate pair is 4 bytes / 2 chars). */
private const val UTF8_MAX_BYTES_PER_CHAR = 3

/**
 * Encodes this text into a fresh [ReadBuffer] using substituting UTF-8.
 *
 * [sizing] controls the allocation strategy; see [SizeHint]. Defaults to [SizeHint.UpperBound]
 * (no measuring pass). Defaults to [BufferFactory.managed] for the same staging-lifetime reason
 * as [String.toReadBuffer].
 */
fun CharSequence.toReadBuffer(
    encoding: Utf8.Lenient,
    sizing: SizeHint = SizeHint.UpperBound,
    factory: BufferFactory = BufferFactory.managed(),
): ReadBuffer {
    if (isEmpty()) return ReadBuffer.EMPTY_BUFFER
    val capacity =
        when (sizing) {
            SizeHint.Exact -> encoding.size(this)
            SizeHint.UpperBound -> length * UTF8_MAX_BYTES_PER_CHAR
            is SizeHint.BytesPerChar -> (length * sizing.ratio).toInt().coerceAtLeast(1)
        }
    val buffer = factory.allocate(capacity)
    buffer.writeText(this, encoding)
    buffer.resetForRead()
    return buffer.slice()
}
