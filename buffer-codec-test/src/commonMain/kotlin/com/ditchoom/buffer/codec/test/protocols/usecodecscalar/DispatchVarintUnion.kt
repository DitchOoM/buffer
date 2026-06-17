package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec

/**
 * Dispatch-union vector for the **runtime-Exact promotion** of `VariableLengthCodec`-backed
 * `@UseCodec` scalar variants. Previously `classifyVariantWireSize` collapsed *any* `@UseCodec`
 * variant to `BackPatch`, so a sealed union of such variants reported `BackPatch` and could not be
 * embedded inside a measure-based / two-pass encoder. Now a `VariableLengthCodec` `@UseCodec`
 * field (here [QuicVarintCodec], which reports `Exact(encodedLength)`) classifies as `RuntimeExact`,
 * so the generated union's `wireSize` forwards to the variant codec and reports `Exact(1 + body)`.
 *
 * Variants pin the three relevant shapes plus the unchanged BackPatch contract:
 *  - [Single]  — sole field is a `VariableLengthCodec` `@UseCodec` scalar → runtime-Exact.
 *  - [Mixed]   — the VL `@UseCodec` scalar sits in a **non-terminal** slot with a fixed scalar last;
 *                without the early-return promotion this would fall to the terminal FixedSize-only
 *                sum and fail `requireFixed`.
 *  - [Marker]  — zero-field `data object` → `Exact(1)` (discriminator only).
 *  - [Plain]   — a plain `Codec` (non-`VariableLengthCodec`) `@UseCodec` scalar → still `BackPatch`.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface DispatchVarintUnion {
    @ProtocolMessage(wireOrder = Endianness.Big)
    @PacketType(0x01)
    data class Single(
        @UseCodec(QuicVarintCodec::class) val v: ULong,
    ) : DispatchVarintUnion

    @ProtocolMessage(wireOrder = Endianness.Big)
    @PacketType(0x02)
    data class Mixed(
        @UseCodec(QuicVarintCodec::class) val v: ULong,
        val flag: Boolean,
    ) : DispatchVarintUnion

    @ProtocolMessage(wireOrder = Endianness.Big)
    @PacketType(0x03)
    data object Marker : DispatchVarintUnion

    @ProtocolMessage(wireOrder = Endianness.Big)
    @PacketType(0x04)
    data class Plain(
        @UseCodec(ZigZagUIntCodec::class) val value: UInt,
    ) : DispatchVarintUnion
}
