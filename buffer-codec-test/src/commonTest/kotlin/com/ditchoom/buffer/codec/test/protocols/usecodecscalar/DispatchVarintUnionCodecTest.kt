package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the runtime-Exact promotion (see [DispatchVarintUnion]): a sealed dispatch union whose
 * variants are `VariableLengthCodec` `@UseCodec` messages reports `Exact(1 + body)`, not `BackPatch`
 * — so it composes inside measure-based encoders — while a plain-`Codec` `@UseCodec` variant keeps
 * its `BackPatch` contract. Plus a round-trip over every variant.
 */
class DispatchVarintUnionCodecTest {
    @Test
    fun variableLengthUseCodecVariantsReportExactUnionWireSize() {
        // Sole VL @UseCodec field: Exact(discriminator + runtime varint width).
        assertEquals(
            WireSize.Exact(1 + QuicVarintCodec.encodedLength(5uL)),
            DispatchVarintUnionCodec.wireSize(DispatchVarintUnion.Single(5uL), EncodeContext.Empty),
        )
        // A 16384 value forces a wider QUIC varint, proving the size is the runtime width (not a
        // compile-time constant), and that the VL field in a non-terminal slot + a trailing fixed
        // Boolean is summed (1 disc + varint + 1 bool) rather than crashing the FixedSize-only path.
        assertEquals(
            WireSize.Exact(1 + QuicVarintCodec.encodedLength(16384uL) + 1),
            DispatchVarintUnionCodec.wireSize(DispatchVarintUnion.Mixed(16384uL, true), EncodeContext.Empty),
        )
        // Zero-field data object → discriminator only.
        assertEquals(
            WireSize.Exact(1),
            DispatchVarintUnionCodec.wireSize(DispatchVarintUnion.Marker, EncodeContext.Empty),
        )
    }

    @Test
    fun nonVariableLengthUseCodecVariantComposesExact() {
        // Plain's user codec declares Exact(4); the dispatcher probes the
        // variant codec and composes 1 (discriminator) + 4 (body). Before the
        // @UseCodec promotion this collapsed to BackPatch at analyze time.
        assertEquals(
            WireSize.Exact(5),
            DispatchVarintUnionCodec.wireSize(DispatchVarintUnion.Plain(1u), EncodeContext.Empty),
        )
    }

    @Test
    fun everyVariantRoundTrips() {
        val samples =
            listOf(
                DispatchVarintUnion.Single(5uL),
                DispatchVarintUnion.Single(16384uL),
                DispatchVarintUnion.Mixed(1uL, false),
                DispatchVarintUnion.Mixed(16384uL, true),
                DispatchVarintUnion.Marker,
                DispatchVarintUnion.Plain(42u),
            )
        for (sample in samples) {
            val buffer = BufferFactory.Default.allocate(64)
            DispatchVarintUnionCodec.encode(buffer, sample, EncodeContext.Empty)
            buffer.resetForRead()
            assertEquals(sample, DispatchVarintUnionCodec.decode(buffer, DecodeContext.Empty), "round-trip $sample")
        }
    }
}
