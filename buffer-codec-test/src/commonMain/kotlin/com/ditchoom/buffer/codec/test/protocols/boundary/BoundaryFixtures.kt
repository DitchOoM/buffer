package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/*
 * Capacity-boundary fixtures for `encodeToPlatformBuffer`'s grow-and-retry
 * loop. BackPatch-shaped messages start from a 64-byte estimate and double
 * on overflow; a `@LengthPrefixed` field whose PREFIX slot straddles the
 * current capacity (position 63/64, 127/128, …) historically failed with a
 * non-retryable exception because the prefix was reserved with a forward
 * `buffer.position(pos + width)` seek instead of placeholder writes.
 *
 * Sweeping the leading field's length walks the second field's prefix
 * across both sides of the 64- and 128-byte boundaries; every point in the
 * sweep must round-trip. (`TwoStrings` in `protocols.simple` covers the
 * default 2-byte-prefix pair; the shapes here cover the 4-byte prefix,
 * the value-class prefix, and the nested-sealed-host composition.)
 */

/** 2-byte-prefixed leader, 4-byte-prefixed tail. */
@ProtocolMessage
data class BigTail(
    @LengthPrefixed val a: String,
    @LengthPrefixed(LengthPrefix.Int) val b: String,
)

@JvmInline
value class BoundaryVid(
    val value: String,
)

/** Value-class-over-String prefix landing at the boundary. */
@ProtocolMessage
data class WithVid(
    @LengthPrefixed val pad: String,
    @LengthPrefixed val id: BoundaryVid,
)

/** Sealed field whose Named variant carries a `@LengthPrefixed String`. */
@ProtocolMessage
sealed interface BoundaryDisp {
    @ProtocolMessage
    @PacketType(0x00)
    data class Named(
        @LengthPrefixed val name: String,
    ) : BoundaryDisp

    @ProtocolMessage
    @PacketType(0x01)
    data object Inherits : BoundaryDisp
}

/** LP string followed by a bare nested sealed field carrying its own LP string. */
@ProtocolMessage
data class BoundaryHost(
    @LengthPrefixed val host: String,
    val disp: BoundaryDisp,
)

/** Nested data-class body whose own field is variable-length → BackPatch wireSize. */
@ProtocolMessage
data class WrappedLabel(
    @LengthPrefixed val label: String,
)

/**
 * Terminal `@LengthPrefixed` nested message with a BackPatch-shaped body.
 * The parent's wireSize must degrade to BackPatch (it used to throw
 * ClassCastException), and encode must reserve-and-back-patch the prefix.
 */
@ProtocolMessage
data class LpWrappedTail(
    val kind: Byte,
    @LengthPrefixed val body: WrappedLabel,
)

/**
 * 1-byte prefix over a nested BackPatch body — exercises the prefix-range
 * guard: a body over 255 bytes must throw `EncodeException`, not silently
 * mask-truncate the prefix.
 */
@ProtocolMessage
data class LpByteWrappedTail(
    @LengthPrefixed(LengthPrefix.Byte) val body: WrappedLabel,
)
