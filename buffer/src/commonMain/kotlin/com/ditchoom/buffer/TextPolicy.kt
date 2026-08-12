package com.ditchoom.buffer

/**
 * A text policy for [WriteBuffer.writeText] and [ReadBuffer.readText]: one object governs both
 * directions, so a field's encode and decode strictness cannot drift apart.
 *
 * The type parameters are the direction results, chosen by the policy — so a lenient operation
 * cannot express failure and a checked result cannot be ignored without the compiler seeing it:
 *
 * - [W]: `writeText` result — `WriteBuffer` (fluent) for [FluentTextPolicy], [TextOutcome] for
 *   [Utf8.Checked].
 * - [D]: `readText` result — `String` for [FluentTextPolicy], [DecodedText] for [Utf8.Checked].
 *
 * **The invariant every platform upholds:** a fluent policy's `size(text)` reports exactly the
 * bytes `writeText(text, policy)` writes; identical bytes/characters for identical inputs on
 * every platform; rejection is atomic (position unchanged).
 *
 * The hierarchy is sealed: every built-in policy is one every platform implements, tests, and
 * may accelerate. Custom behavior enters through [custom], which composes a [TextEncoder] and a
 * [TextDecoder] — the sealed wrapper keeps platform dispatch exhaustive while the interfaces
 * stay freely implementable. Custom transcoders are never platform-accelerated and their
 * byte contract is the author's responsibility (validate it with `TextTranscoderContractKit`).
 */
sealed class TextPolicy<out W, out D> {
    /** Maps a successful write of [byteCount] bytes into the policy's write-result type. */
    internal abstract fun written(
        buffer: WriteBuffer,
        byteCount: Int,
    ): W

    /** Maps a rejected write (first unpaired surrogate at UTF-16 [index]) into the write-result type. */
    internal abstract fun malformedWrite(index: Int): W

    /** Maps a successful decode into the policy's read-result type. */
    internal abstract fun decoded(value: String): D

    /** Maps a rejected decode (ill-formed subsequence starting at [byteOffset]) into the read-result type. */
    internal abstract fun malformedRead(byteOffset: Int): D

    /**
     * Re-binds a fluent write result produced against a wrapped buffer to the wrapper [self].
     * Wrappers (PooledBuffer, TrackedSlice) route their delegated `writeText` result through
     * this so fluent policies return the wrapper — never the unwrapped buffer.
     */
    internal open fun rewrap(
        result: @UnsafeVariance W,
        self: WriteBuffer,
    ): W = result

    companion object {
        /**
         * Composes a policy from independently authored halves. Either half can be a built-in
         * one (see [FluentTextPolicy.encoder]/[FluentTextPolicy.decoder]) — mix a custom encoder
         * with a vetted library decoder or the reverse.
         */
        fun custom(
            encoder: TextEncoder,
            decoder: TextDecoder,
        ): FluentTextPolicy = CustomTextPolicy(encoder, decoder)
    }
}

/**
 * The fluent policy family: writes chain on the buffer, reads produce the [String] directly, and
 * failures — if the policy can fail at all — surface as thrown [MalformedTextException]. This is
 * the only family codec-generated code accepts: a generated linear encode body has no channel
 * for a checked result, and the `TextPolicy<WriteBuffer, String>` bound enforces that statically.
 */
sealed class FluentTextPolicy : TextPolicy<WriteBuffer, String>() {
    /**
     * Exactly the bytes `writeText(text, this)` writes — on every platform. [Utf8.Strict] throws
     * [MalformedTextException] here for ill-formed input, preserving size == write agreement:
     * both halves of the invariant fail identically.
     */
    abstract fun size(text: CharSequence): Int

    /** This policy's encode half, reusable in [TextPolicy.custom]. */
    val encoder: TextEncoder by lazy {
        object : TextEncoder {
            override fun encodeInto(
                buffer: WriteBuffer,
                text: CharSequence,
            ): Int {
                val start = buffer.position()
                buffer.writeText(text, this@FluentTextPolicy)
                return buffer.position() - start
            }

            override fun size(text: CharSequence): Int = this@FluentTextPolicy.size(text)
        }
    }

    /** This policy's decode half, reusable in [TextPolicy.custom]. */
    val decoder: TextDecoder by lazy {
        object : TextDecoder {
            override fun decodeFrom(
                buffer: ReadBuffer,
                length: Int,
            ): String = buffer.readText(length, this@FluentTextPolicy)
        }
    }

    final override fun written(
        buffer: WriteBuffer,
        byteCount: Int,
    ): WriteBuffer = buffer

    final override fun rewrap(
        result: WriteBuffer,
        self: WriteBuffer,
    ): WriteBuffer = self

    final override fun decoded(value: String): String = value
}

/** UTF-8 [TextPolicy] singletons. */
object Utf8 {
    /**
     * Substituting UTF-8. Cannot fail in either direction:
     * - write: each unpaired surrogate becomes U+FFFD (three bytes);
     * - read: ill-formed byte sequences become U+FFFD per the WHATWG/Unicode maximal-subpart rule.
     *
     * Identical bytes and characters on every platform.
     */
    object Lenient : FluentTextPolicy() {
        override fun size(text: CharSequence): Int = Utf8TextEncoder.sizeSubstituting(text)

        override fun malformedWrite(index: Int): Nothing = error("unreachable: Lenient substitutes")

        override fun malformedRead(byteOffset: Int): Nothing = error("unreachable: Lenient substitutes")
    }

    /**
     * Validating UTF-8 that rejects loudly: ill-formed input throws [MalformedTextException] —
     * from `writeText`, `readText`, AND [size] — always atomically (position unchanged, nothing
     * written or consumed). Well-formed input behaves exactly like [Lenient]. The one-liner for
     * callers whose answer to malformed input is "that's an error".
     */
    object Strict : FluentTextPolicy() {
        override fun size(text: CharSequence): Int {
            val bad = Utf8TextEncoder.firstMalformedIndex(text)
            if (bad >= 0) throw MalformedTextException.UnpairedSurrogate(bad)
            return Utf8TextEncoder.sizeSubstituting(text)
        }

        override fun malformedWrite(index: Int): Nothing = throw MalformedTextException.UnpairedSurrogate(index)

        override fun malformedRead(byteOffset: Int): Nothing = throw MalformedTextException.IllFormedBytes(byteOffset)
    }

    /**
     * Validating UTF-8 that reports failure as data: `writeText` returns [TextOutcome],
     * `readText` returns [DecodedText], rejection is atomic. For callers that branch on
     * malformed input programmatically (repair, truncate-at-index, protocol rejection).
     */
    object Checked : TextPolicy<TextOutcome, DecodedText>() {
        /**
         * [TextOutcome.Bytes] with exactly the bytes `writeText(text, Checked)` writes, or
         * [TextOutcome.Malformed] with the UTF-16 index of the first unpaired surrogate.
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

        override fun malformedWrite(index: Int): TextOutcome = TextOutcome.Malformed(index)

        override fun decoded(value: String): DecodedText = DecodedText.Text(value)

        override fun malformedRead(byteOffset: Int): DecodedText = DecodedText.Malformed(byteOffset)
    }
}

/** The sealed wrapper for [TextPolicy.custom]. Dispatch routes straight to the author's halves. */
internal class CustomTextPolicy(
    val customEncoder: TextEncoder,
    val customDecoder: TextDecoder,
) : FluentTextPolicy() {
    override fun size(text: CharSequence): Int = customEncoder.size(text)

    override fun malformedWrite(index: Int): Nothing = error("unreachable: custom handles malformed input")

    override fun malformedRead(byteOffset: Int): Nothing = error("unreachable: custom handles malformed input")
}

/**
 * Thrown by [Utf8.Strict] (and custom transcoders that choose to) for ill-formed input.
 *
 * Sealed and string-free: catch the base type, branch exhaustively on the subtype — each
 * failure kind carries its own typed location. Messages are derived from the typed fields
 * for logs and never carry information the type does not.
 */
sealed class MalformedTextException : Exception() {
    /** Write-side failure: the input CharSequence has an unpaired surrogate at [charIndex]. */
    class UnpairedSurrogate(
        val charIndex: Int,
    ) : MalformedTextException() {
        override val message: String get() = "Unpaired surrogate at UTF-16 index $charIndex"
    }

    /**
     * Read-side failure: the bytes contain an ill-formed UTF-8 subsequence starting at
     * [byteOffset], relative to the read window.
     */
    class IllFormedBytes(
        val byteOffset: Int,
    ) : MalformedTextException() {
        override val message: String get() = "Ill-formed UTF-8 subsequence at byte offset $byteOffset"
    }
}

/** Result of a checked text write (or [Utf8.Checked.size]): byte count, or where the input is ill-formed. */
sealed interface TextOutcome {
    /** The operation covers exactly [count] bytes. */
    data class Bytes(
        val count: Int,
    ) : TextOutcome

    /** Ill-formed UTF-16 input; [index] is the first unpaired surrogate. Nothing was written. */
    data class Malformed(
        val index: Int,
    ) : TextOutcome
}

/** Result of a checked text read: the decoded value, or where the bytes are ill-formed. */
sealed interface DecodedText {
    /** The window decoded cleanly. */
    data class Text(
        val value: String,
    ) : DecodedText

    /** Ill-formed UTF-8; [byteOffset] starts the first ill-formed subsequence. Nothing was consumed. */
    data class Malformed(
        val byteOffset: Int,
    ) : DecodedText
}

/**
 * The encode half of a custom [TextPolicy]. Implementations write [text] to [buffer] —
 * substituting, rejecting (throw), or transforming however they define — and return the bytes
 * written.
 *
 * [size] is DERIVED from [encodeInto] by default (run against a counting sink), so it cannot
 * disagree with the write — the under-count bug class is unrepresentable unless you override
 * [size], in which case `TextTranscoderContractKit` verifies the override.
 */
interface TextEncoder {
    /** Writes [text] to [buffer] at its position; returns bytes written (position advances by it). */
    fun encodeInto(
        buffer: WriteBuffer,
        text: CharSequence,
    ): Int

    /** Exactly the bytes [encodeInto] writes. Default: derived by running the encode without stores. */
    fun size(text: CharSequence): Int = encodeInto(CountingWriteBuffer(), text)
}

/** The decode half of a custom [TextPolicy]. */
interface TextDecoder {
    /**
     * Decodes [length] bytes from [buffer]'s position (advancing it by [length] on success).
     * Failure handling — substitute, throw, resync — is the implementation's contract.
     */
    fun decodeFrom(
        buffer: ReadBuffer,
        length: Int,
    ): String
}

/** Allocation sizing strategy for [toReadBuffer]-style operations that allocate before writing. */
sealed interface SizeHint {
    /** One measuring pass over the text, then allocate precisely. Minimal memory, two passes. */
    data object Exact : SizeHint

    /** No measuring pass; allocate the worst case. The measured-fastest default on every platform. */
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

/**
 * Shared [WriteBuffer.writeText] dispatch: custom routing, policy selection, strict/checked
 * pre-validation (atomic rejection), and the outcome mapping. Platform overrides supply only
 * [substitutingWrite] — their fastest "encode with U+FFFD substitution, return bytes written"
 * primitive. The sentinel Int (bytes written, or first-malformed `~index`) is internal to
 * implementations; the public surface is policy-typed.
 */
internal inline fun <W> WriteBuffer.dispatchWriteText(
    text: CharSequence,
    policy: TextPolicy<W, *>,
    substitutingWrite: (CharSequence) -> Int,
): W {
    if (policy is CustomTextPolicy) {
        // Custom transcoders bypass platform machinery entirely: the author's encode runs as
        // written, against this buffer's primitives.
        return policy.written(this, policy.customEncoder.encodeInto(this, text))
    }
    val outcome =
        when (policy) {
            Utf8.Lenient -> substitutingWrite(text)
            Utf8.Strict, Utf8.Checked -> {
                val bad = Utf8TextEncoder.firstMalformedIndex(text)
                if (bad >= 0) bad.inv() else substitutingWrite(text)
            }
            is CustomTextPolicy -> error("unreachable: handled above")
        }
    return if (outcome >= 0) policy.written(this, outcome) else policy.malformedWrite(outcome.inv())
}

/**
 * Shared [ReadBuffer.readText] dispatch: custom routing, zero-copy managed-array access,
 * single-pass checked decode, and atomic rejection (position unchanged). Native-memory buffers
 * stage one copy through [ReadBuffer.readByteArray] — the only generic access path; platform
 * overrides may remove it with a measured-identical fast path.
 */
@Suppress("ReturnCount") // early return per policy on the rare malformed path is the readable form
internal fun <D> dispatchReadText(
    buffer: ReadBuffer,
    length: Int,
    policy: TextPolicy<*, D>,
): D {
    if (policy is CustomTextPolicy) {
        return policy.decoded(policy.customDecoder.decodeFrom(buffer, length))
    }
    val start = buffer.position()
    val managed = buffer.managedMemoryAccess
    val bytes: ByteArray
    val bytesOffset: Int
    if (managed != null) {
        if (buffer.remaining() < length) {
            throw BufferUnderflowException(
                "Buffer underflow: cannot read $length byte(s) at position $start " +
                    "(limit=${buffer.limit()}, remaining=${buffer.remaining()})",
            )
        }
        bytes = managed.backingArray
        bytesOffset = managed.arrayOffset + start
    } else {
        bytes = buffer.readByteArray(length) // advances; rewound below on rejection
        bytesOffset = 0
    }
    // One pass on the happy path: the stdlib's STRICT decode validates while decoding, at
    // native-optimized speed on every platform (the reference state machine's scalar scan is
    // measured ~10x slower on Kotlin/Native). Exact for well-formed input by definition; the
    // reference decoder runs only on the rare ill-formed path, where it is the single
    // authority for offsets and U+FFFD placement.
    val value =
        try {
            bytes.decodeToString(bytesOffset, bytesOffset + length, throwOnInvalidSequence = true)
        } catch (
            @Suppress("SwallowedException") e: CharacterCodingException,
        ) {
            return when (policy) {
                Utf8.Lenient -> {
                    val substituted = Utf8TextDecoder.decodeSubstituting(bytes, bytesOffset, length)
                    if (managed != null) buffer.position(start + length)
                    policy.decoded(substituted)
                }
                Utf8.Strict, Utf8.Checked -> {
                    // Atomic rejection: nothing consumed.
                    buffer.position(start)
                    policy.malformedRead(Utf8TextDecoder.firstMalformedOffset(bytes, bytesOffset, length))
                }
                is CustomTextPolicy -> error("unreachable: handled above")
            }
        }
    if (managed != null) buffer.position(start + length)
    return policy.decoded(value)
}

/** Writes [text] as substituting UTF-8 — the drop-in replacement for `writeString(text)`. */
fun WriteBuffer.writeText(text: CharSequence): WriteBuffer = writeText(text, Utf8.Lenient)

/** Reads [length] bytes as substituting UTF-8 — the drop-in replacement for `readString(length)`. */
fun ReadBuffer.readText(length: Int): String = readText(length, Utf8.Lenient)

/** Exactly the bytes a lenient UTF-8 write of this text produces, on every platform. */
fun CharSequence.utf8Size(): Int = Utf8.Lenient.size(this)

/** UTF-8 bytes-per-char upper bound under substitution (a surrogate pair is 4 bytes / 2 chars). */
private const val UTF8_MAX_BYTES_PER_CHAR = 3

/**
 * Encodes this text into a fresh [ReadBuffer] under [policy] (any fluent policy: [Utf8.Lenient],
 * [Utf8.Strict], or a [TextPolicy.custom] composition).
 *
 * [sizing] defaults to [SizeHint.UpperBound] — the measured-fastest strategy on every platform
 * (on Apple and Linux the exact-size pass costs more than the encode itself); choose
 * [SizeHint.Exact] when memory is the constraint. Defaults to [BufferFactory.managed] for the
 * same staging-lifetime reason as [String.toReadBuffer].
 */
fun CharSequence.toReadBuffer(
    policy: FluentTextPolicy,
    sizing: SizeHint = SizeHint.UpperBound,
    factory: BufferFactory = BufferFactory.managed(),
): ReadBuffer {
    if (isEmpty()) return ReadBuffer.EMPTY_BUFFER
    val capacity =
        when (sizing) {
            SizeHint.Exact -> policy.size(this)
            // A custom transcoder's expansion ratio is unknowable here, so UpperBound falls back
            // to its (derived) exact size rather than assuming the UTF-8 worst case.
            SizeHint.UpperBound ->
                if (policy is CustomTextPolicy) policy.size(this) else length * UTF8_MAX_BYTES_PER_CHAR
            is SizeHint.BytesPerChar -> (length * sizing.ratio).toInt().coerceAtLeast(1)
        }
    val buffer = factory.allocate(capacity)
    buffer.writeText(this, policy)
    buffer.resetForRead()
    return buffer.slice()
}
