package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * Stage E slice 3 doctrine vector — `@WhenTrue` against the dotted
 * `<sibling>.<property>` form, where `<sibling>` is a `@JvmInline
 * value class` exposing a `Boolean`-returning `val` property
 * (Locked Decision row 19, dotted clause).
 *
 * Smallest-possible shape that exercises both new emitter
 * capabilities at once:
 *   1. Value-class-as-field on a parent data class
 *      (`flags: SmallFlags`, decoded as a single inner UByte and
 *      reconstructed via the value class's primary constructor).
 *   2. Dotted-form `@WhenTrue("flags.want")` resolving against the
 *      reconstructed value class's `want: Boolean` property.
 *
 * Wire layout:
 *   - `WithFlagPayload(flags = SmallFlags(0))`             → `00`
 *   - `WithFlagPayload(flags = SmallFlags(1), payload = N)` → `01 NN NN NN NN`
 *
 * Slice 3 keeps the bound parameter inner shape narrow on purpose
 * (`payload: Int?` is a natural-width Scalar — the slice 2
 * restriction). MQTT v3 CONNECT's `@LengthPrefixed`-inner shape
 * lands in slice 5; this fixture validates only the dotted-form
 * resolver and the value-class-field round-trip.
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
    @WhenTrue("flags.want") val payload: Int? = null,
)
