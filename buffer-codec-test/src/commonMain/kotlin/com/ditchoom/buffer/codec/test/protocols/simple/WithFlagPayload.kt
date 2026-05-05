package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.When
import kotlin.jvm.JvmInline

/**
 * Stage E slice 3 + 3.5 doctrine vector — `@When` against the
 * dotted `<sibling>.<property>` form, where `<sibling>` is a
 * `@JvmInline value class` exposing a `Boolean`-returning `val`
 * property (Locked Decision row 19, dotted clause), composed with
 * the `@LengthPrefixed val: String?` inner-shape widening (row 19,
 * inner type universe).
 *
 * Smallest-possible shape that exercises both emitter capabilities
 * at once:
 *   1. Value-class-as-field on a parent data class
 *      (`flags: SmallFlags`, decoded as a single inner UByte and
 *      reconstructed via the value class's primary constructor).
 *   2. Dotted-form `@When("flags.want")` resolving against the
 *      reconstructed value class's `want: Boolean` property.
 *   3. `@LengthPrefixed`-inner `@When` slot — the predicate
 *      gates an entire length-prefixed UTF-8 string body
 *      (BackPatch encode, Int.MAX_VALUE-guarded decode).
 *
 * Wire layout (default LengthPrefix.Short, 2-byte big-endian
 * prefix):
 *   - `WithFlagPayload(flags = SmallFlags(0))`             → `00`
 *   - `WithFlagPayload(flags = SmallFlags(1), payload = "")`   → `01 00 00`
 *   - `WithFlagPayload(flags = SmallFlags(1), payload = "hi")` → `01 00 02 68 69`
 *
 * Predicate-false collapses the entire slot (prefix included) to
 * zero bytes per Locked Decision row 19.
 */
@JvmInline
@ProtocolMessage
value class SmallFlags(
    val raw: UByte,
) {
    /** Lowest bit set ⇔ the optional `payload` is present on the wire. */
    val want: Boolean get() = (raw.toInt() and 0x01) != 0

    companion object {
        val NONE = SmallFlags(0u)
        val WANT = SmallFlags(1u)
    }
}

@ProtocolMessage
data class WithFlagPayload(
    val flags: SmallFlags,
    @LengthPrefixed @When("flags.want") val payload: String? = null,
)
