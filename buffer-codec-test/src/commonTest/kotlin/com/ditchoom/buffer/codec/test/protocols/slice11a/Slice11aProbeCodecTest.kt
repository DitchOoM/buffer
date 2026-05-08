package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Round-trip + wire-byte + wireSize coverage
 * for the emitter widening on `@When @UseCodec val: T?` (sealed inner)
 * and `@RemainingBytes val: List<E>` (sealed element). The fixtures
 * are in [Slice11aProbe.kt] — pure capability slice, byte-identical
 * generated code for the v5 fixtures.
 */
class Slice11aProbeCodecTest {
    @Test
    fun probeConditionalEncodesAbsentAsZeroByte() {
        // present=false → writes the Boolean byte and no seal body.
        val msg = ProbeConditional(present = false, seal = null)
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(byteArrayOf(0x00), buf.readByteArray(buf.remaining()))
    }

    @Test
    fun probeConditionalRoundTripsTag1() {
        // present=true, seal=Tag1(0x77): 01 (present) 01 (Tag1) 77.
        val original = ProbeConditional(present = true, seal = ProbeSealed.Tag1(n = 0x77u))
        val buf = encode(original)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x01, 0x01, 0x77),
            buf.readByteArray(buf.remaining()),
        )
        buf.resetForRead()
        val decoded = ProbeConditionalCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun probeConditionalRoundTripsTag2() {
        // BackPatch sealed variant — decoding via ProbeSealedDelegateCodec
        // exercises the full Conditional-with-UseCodec emit path.
        val original = ProbeConditional(present = true, seal = ProbeSealed.Tag2(msg = "hi"))
        val buf = encode(original)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x00, 0x02, 'h'.code.toByte(), 'i'.code.toByte()),
            buf.readByteArray(buf.remaining()),
        )
        buf.resetForRead()
        val decoded = ProbeConditionalCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun probeRemainingBytesListRoundTripsMixedVariants() {
        // Both Exact and BackPatch sealed variants in the same list. The
        // wireSize short-circuit adds collapses to BackPatch
        // without it, the runtime `as Exact` cast on Tag2's wireSize CCEs.
        val original =
            ProbeRemainingBytesList(
                xs =
                    listOf(
                        ProbeSealed.Tag1(n = 0x42u),
                        ProbeSealed.Tag2(msg = "ab"),
                        ProbeSealed.Tag1(n = 0xFFu),
                    ),
            )
        val buf = encode(original)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(
                0x01,
                0x42, // Tag1(n=0x42)
                0x02,
                0x00,
                0x02,
                'a'.code.toByte(),
                'b'.code.toByte(), // Tag2(msg="ab")
                0x01,
                0xFF.toByte(), // Tag1(n=0xFF)
            ),
            buf.readByteArray(buf.remaining()),
        )
        buf.resetForRead()
        val decoded = ProbeRemainingBytesListCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun probeRemainingBytesListWireSizeReportsBackPatchForSealedElements() {
        // The guard in `buildWireSizeFun` returns BackPatch when
        // any RemainingBytesProtocolMessageList field has a BackPatch
        // element. ProbeSealed.Tag2 is BackPatch (carries
        // @LengthPrefixed val: String), so the collapse fires.
        val msg = ProbeRemainingBytesList(xs = listOf(ProbeSealed.Tag1(n = 0x00u)))
        val ws = ProbeRemainingBytesListCodec.wireSize(msg, EncodeContext.Empty)
        assertIs<WireSize.BackPatch>(ws, "expected BackPatch from sealed-element widening")
    }

    @Test
    fun probeConditionalWireSizeReportsBackPatch() {
        // Any Conditional field collapses message wireSize to BackPatch
        // (row 19) regardless of the inner shape — sanity check.
        val msg = ProbeConditional(present = true, seal = ProbeSealed.Tag1(n = 0x00u))
        val ws = ProbeConditionalCodec.wireSize(msg, EncodeContext.Empty)
        assertIs<WireSize.BackPatch>(ws)
    }

    private fun encode(value: ProbeConditional) =
        BufferFactory.Default
            .allocate(32, ByteOrder.BIG_ENDIAN)
            .also { ProbeConditionalCodec.encode(it, value, EncodeContext.Empty) }

    private fun encode(value: ProbeRemainingBytesList) =
        BufferFactory.Default
            .allocate(32, ByteOrder.BIG_ENDIAN)
            .also { ProbeRemainingBytesListCodec.encode(it, value, EncodeContext.Empty) }
}
