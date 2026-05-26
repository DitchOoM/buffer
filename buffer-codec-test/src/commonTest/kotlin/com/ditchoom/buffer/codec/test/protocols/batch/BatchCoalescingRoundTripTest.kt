package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BatchCoalescingRoundTripTest {
    private fun encode(
        size: Int,
        block: (com.ditchoom.buffer.PlatformBuffer) -> Unit,
    ): com.ditchoom.buffer.PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(size)
        block(buffer)
        buffer.resetForRead()
        return buffer
    }

    @Test
    fun fourUBytesRoundTrip() {
        val original = FourUBytes(0xDEu, 0xADu, 0xBEu, 0xEFu)
        val buffer = encode(4) { FourUBytesCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = FourUBytesCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun eightUBytesRoundTrip() {
        val original = EightUBytes(0x01u, 0x23u, 0x45u, 0x67u, 0x89u, 0xABu, 0xCDu, 0xEFu)
        val buffer = encode(8) { EightUBytesCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = EightUBytesCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun mixedNaturalScalarsRoundTrip() {
        val original = MixedNaturalScalars(flags = 0xA5u, tag = 0x5Au, length = 0x1234u, checksum = 0xDEADBEEFu)
        val buffer = encode(8) { MixedNaturalScalarsCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = MixedNaturalScalarsCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun valueClassBatchRoundTrip() {
        val original = ValueClassBatch(header = HeaderByte(0x42u), tail = HeaderByte(0x7Fu))
        val buffer = encode(2) { ValueClassBatchCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = ValueClassBatchCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun conditionalBreaksBatchPresent() {
        val original = ConditionalBreaksBatch(header = 0x11u, hasExtra = true, extra = 0x22u, trailer = 0x33u)
        val buffer = encode(8) { ConditionalBreaksBatchCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = ConditionalBreaksBatchCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun conditionalBreaksBatchAbsent() {
        val original = ConditionalBreaksBatch(header = 0x11u, hasExtra = false, extra = null, trailer = 0x33u)
        val buffer = encode(8) { ConditionalBreaksBatchCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = ConditionalBreaksBatchCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun signedAndUnsignedMixRoundTrip() {
        val original = SignedAndUnsignedMix(signed = (-1).toByte(), unsigned = 0xFFu, signedShort = Short.MIN_VALUE)
        val buffer = encode(4) { SignedAndUnsignedMixCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = SignedAndUnsignedMixCodec.decode(buffer, DecodeContext.Empty)
        assertEquals((-1).toByte(), decoded.signed)
        assertEquals(0xFFu.toUByte(), decoded.unsigned)
        assertEquals(Short.MIN_VALUE, decoded.signedShort)
    }

    @Test
    fun fourUBytesEncodesToExactWireBytes() {
        val original = FourUBytes(0xDEu, 0xADu, 0xBEu, 0xEFu)
        val buffer = encode(4) { FourUBytesCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(4, buffer.remaining())
        assertEquals(0xDE.toByte(), buffer.readByte())
        assertEquals(0xAD.toByte(), buffer.readByte())
        assertEquals(0xBE.toByte(), buffer.readByte())
        assertEquals(0xEF.toByte(), buffer.readByte())
    }

    @Test
    fun decodeFourUBytesFromExactWireBytes() {
        val src = BufferFactory.Default.allocate(4)
        src.writeByte(0xDE.toByte())
        src.writeByte(0xAD.toByte())
        src.writeByte(0xBE.toByte())
        src.writeByte(0xEF.toByte())
        src.resetForRead()
        val decoded = FourUBytesCodec.decode(src, DecodeContext.Empty)
        assertEquals(FourUBytes(0xDEu, 0xADu, 0xBEu, 0xEFu), decoded)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case-2 (same-shared-order) coverage. Each test runs the round-trip
    // under both BIG_ENDIAN and LITTLE_ENDIAN buffers so the byte-swap-
    // aware bulk read/write is genuinely exercised on both paths. Wire-
    // byte assertions pin the canonical layout: `Big` fixtures emit big-
    // endian bytes regardless of buffer order, `Little` emits little-
    // endian bytes regardless. The fresh-eyes review flagged this as the
    // case the v5 worktree port silently skipped — every TCP/IP/TLS-style
    // protocol with an explicit wire order would never have batched.

    private val bigHeaderSample =
        BigHeader(
            type = 0x12u,
            version = 0x34u,
            flags = 0x5678u,
            length = 0x9ABCDEF0u,
        )

    private val bigHeaderWire: ByteArray =
        byteArrayOf(
            0x12,
            0x34,
            0x56,
            0x78.toByte(),
            0x9A.toByte(),
            0xBC.toByte(),
            0xDE.toByte(),
            0xF0.toByte(),
        )

    private val littleHeaderSample =
        LittleHeader(
            type = 0x12u,
            version = 0x34u,
            flags = 0x5678u,
            length = 0x9ABCDEF0u,
        )

    private val littleHeaderWire: ByteArray =
        // type=0x12 (1B), version=0x34 (1B), flags=0x5678 LE = 78 56,
        // length=0x9ABCDEF0 LE = F0 DE BC 9A
        byteArrayOf(
            0x12,
            0x34,
            0x78,
            0x56,
            0xF0.toByte(),
            0xDE.toByte(),
            0xBC.toByte(),
            0x9A.toByte(),
        )

    private fun encodeIntoOrder(
        size: Int,
        order: ByteOrder,
        block: (com.ditchoom.buffer.PlatformBuffer) -> Unit,
    ): com.ditchoom.buffer.PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(size, order)
        block(buffer)
        buffer.resetForRead()
        return buffer
    }

    private fun decodeBytes(
        bytes: ByteArray,
        order: ByteOrder,
    ): com.ditchoom.buffer.PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size, order)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    @Test
    fun bigHeader_encodes_canonicalBigWire_underBigBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.BIG_ENDIAN) {
                BigHeaderCodec.encode(it, bigHeaderSample, EncodeContext.Empty)
            }
        assertContentEquals(bigHeaderWire, buf.readByteArray(8))
    }

    @Test
    fun bigHeader_encodes_canonicalBigWire_underLittleBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                BigHeaderCodec.encode(it, bigHeaderSample, EncodeContext.Empty)
            }
        assertContentEquals(
            bigHeaderWire,
            buf.readByteArray(8),
            "wireOrder=Big must beat buffer.byteOrder=Little",
        )
    }

    @Test
    fun bigHeader_decodes_canonicalBigWire_underBigBuffer() {
        val src = decodeBytes(bigHeaderWire, ByteOrder.BIG_ENDIAN)
        assertEquals(bigHeaderSample, BigHeaderCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun bigHeader_decodes_canonicalBigWire_underLittleBuffer() {
        val src = decodeBytes(bigHeaderWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(
            bigHeaderSample,
            BigHeaderCodec.decode(src, DecodeContext.Empty),
            "wireOrder=Big must beat buffer.byteOrder=Little",
        )
    }

    @Test
    fun littleHeader_encodes_canonicalLittleWire_underLittleBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                LittleHeaderCodec.encode(it, littleHeaderSample, EncodeContext.Empty)
            }
        assertContentEquals(littleHeaderWire, buf.readByteArray(8))
    }

    @Test
    fun littleHeader_encodes_canonicalLittleWire_underBigBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.BIG_ENDIAN) {
                LittleHeaderCodec.encode(it, littleHeaderSample, EncodeContext.Empty)
            }
        assertContentEquals(
            littleHeaderWire,
            buf.readByteArray(8),
            "wireOrder=Little must beat buffer.byteOrder=Big",
        )
    }

    @Test
    fun littleHeader_decodes_canonicalLittleWire_underLittleBuffer() {
        val src = decodeBytes(littleHeaderWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(littleHeaderSample, LittleHeaderCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun littleHeader_decodes_canonicalLittleWire_underBigBuffer() {
        val src = decodeBytes(littleHeaderWire, ByteOrder.BIG_ENDIAN)
        assertEquals(
            littleHeaderSample,
            LittleHeaderCodec.decode(src, DecodeContext.Empty),
            "wireOrder=Little must beat buffer.byteOrder=Big",
        )
    }

    @Test
    fun defaultOrderMixedScalars_roundTrips_underLittleBuffer() {
        // Latent-bug guard: the pre-fix Default-order batched read silently
        // field-swapped on LITTLE_ENDIAN buffers. After the fix, Default-
        // order batches branch on buffer.byteOrder and produce the same
        // values as the single-scalar Default path. This test exercises
        // the LE arm of the branched extraction explicitly.
        val original = MixedNaturalScalars(flags = 0xA5u, tag = 0x5Au, length = 0x1234u, checksum = 0xDEADBEEFu)
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                MixedNaturalScalarsCodec.encode(it, original, EncodeContext.Empty)
            }
        assertEquals(original, MixedNaturalScalarsCodec.decode(buf, DecodeContext.Empty))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mixed-wire-order gate coverage. Each test asserts exact wire bytes
    // against a hand-derived spec layout under both buffer orders. A gate
    // bug that batched across order boundaries would byte-swap the middle
    // field and fail these.

    private val mixedFlushSample =
        MixedOrderFlush(
            leadingBig = 0x1234u,
            middleLittle = 0xABCDu,
            trailingBig = 0x56789ABCu,
        )

    private val mixedFlushWire: ByteArray =
        byteArrayOf(
            // leadingBig = 0x1234 (Big): 12 34
            0x12,
            0x34,
            // middleLittle = 0xABCD (Little): CD AB
            0xCD.toByte(),
            0xAB.toByte(),
            // trailingBig = 0x56789ABC (Big): 56 78 9A BC
            0x56,
            0x78,
            0x9A.toByte(),
            0xBC.toByte(),
        )

    @Test
    fun mixedOrderFlush_wireBytes_BigBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.BIG_ENDIAN) {
                MixedOrderFlushCodec.encode(it, mixedFlushSample, EncodeContext.Empty)
            }
        assertContentEquals(mixedFlushWire, buf.readByteArray(8))
    }

    @Test
    fun mixedOrderFlush_wireBytes_LittleBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                MixedOrderFlushCodec.encode(it, mixedFlushSample, EncodeContext.Empty)
            }
        assertContentEquals(
            mixedFlushWire,
            buf.readByteArray(8),
            "field-level @WireOrder overrides must beat buffer.byteOrder regardless",
        )
    }

    @Test
    fun mixedOrderFlush_decode_BigBuffer() {
        val src = decodeBytes(mixedFlushWire, ByteOrder.BIG_ENDIAN)
        assertEquals(mixedFlushSample, MixedOrderFlushCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun mixedOrderFlush_decode_LittleBuffer() {
        val src = decodeBytes(mixedFlushWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(mixedFlushSample, MixedOrderFlushCodec.decode(src, DecodeContext.Empty))
    }

    private val mixedValueClassSample =
        MixedOrderValueClass(
            leadingBig = 0x1234u,
            littleTag = LittleTag(0xABCDu),
            trailingBig = 0x56789ABCu,
        )

    @Test
    fun mixedOrderValueClass_wireBytes_BigBuffer() {
        // Same wire layout as MixedOrderFlush: parent Big + middle Little
        // (carried via the value class's own wireOrder) + trailing Big.
        val buf =
            encodeIntoOrder(8, ByteOrder.BIG_ENDIAN) {
                MixedOrderValueClassCodec.encode(it, mixedValueClassSample, EncodeContext.Empty)
            }
        assertContentEquals(mixedFlushWire, buf.readByteArray(8))
    }

    @Test
    fun mixedOrderValueClass_wireBytes_LittleBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                MixedOrderValueClassCodec.encode(it, mixedValueClassSample, EncodeContext.Empty)
            }
        assertContentEquals(
            mixedFlushWire,
            buf.readByteArray(8),
            "value-class wireOrder must beat parent's order and buffer.byteOrder",
        )
    }

    @Test
    fun mixedOrderValueClass_decode_BigBuffer() {
        val src = decodeBytes(mixedFlushWire, ByteOrder.BIG_ENDIAN)
        assertEquals(mixedValueClassSample, MixedOrderValueClassCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun mixedOrderValueClass_decode_LittleBuffer() {
        val src = decodeBytes(mixedFlushWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(mixedValueClassSample, MixedOrderValueClassCodec.decode(src, DecodeContext.Empty))
    }

    private val partialBatchSample =
        MixedOrderPartialBatch(
            bigA = 0x1234u,
            bigB = 0x5678u,
            trailingLittle = 0x9ABCDEF0u,
        )

    private val partialBatchWire: ByteArray =
        byteArrayOf(
            // bigA = 0x1234 (Big): 12 34
            0x12,
            0x34,
            // bigB = 0x5678 (Big): 56 78  (batched with bigA → readInt)
            0x56,
            0x78,
            // trailingLittle = 0x9ABCDEF0 (Little): F0 DE BC 9A
            0xF0.toByte(),
            0xDE.toByte(),
            0xBC.toByte(),
            0x9A.toByte(),
        )

    @Test
    fun partialBatch_wireBytes_BigBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.BIG_ENDIAN) {
                MixedOrderPartialBatchCodec.encode(it, partialBatchSample, EncodeContext.Empty)
            }
        assertContentEquals(partialBatchWire, buf.readByteArray(8))
    }

    @Test
    fun partialBatch_wireBytes_LittleBuffer() {
        val buf =
            encodeIntoOrder(8, ByteOrder.LITTLE_ENDIAN) {
                MixedOrderPartialBatchCodec.encode(it, partialBatchSample, EncodeContext.Empty)
            }
        assertContentEquals(partialBatchWire, buf.readByteArray(8))
    }

    @Test
    fun partialBatch_decode_BigBuffer() {
        val src = decodeBytes(partialBatchWire, ByteOrder.BIG_ENDIAN)
        assertEquals(partialBatchSample, MixedOrderPartialBatchCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun partialBatch_decode_LittleBuffer() {
        val src = decodeBytes(partialBatchWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(partialBatchSample, MixedOrderPartialBatchCodec.decode(src, DecodeContext.Empty))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Interaction coverage: sandwich (batch / conditional / batch),
    // @FramedBy + batching, sealed dispatch with batchable variant bodies.

    private val sandwichPresentSample =
        SandwichBatch(
            a = 0x1111u,
            b = 0x2222u,
            gate = true,
            middle = 0xDEADBEEFu,
            c = 0x3333u,
            d = 0x4444u,
        )

    private val sandwichPresentWire: ByteArray =
        byteArrayOf(
            // a+b batched, Big: 11 11 22 22
            0x11,
            0x11,
            0x22,
            0x22,
            // gate=true
            0x01,
            // middle=0xDEADBEEF Big: DE AD BE EF
            0xDE.toByte(),
            0xAD.toByte(),
            0xBE.toByte(),
            0xEF.toByte(),
            // c+d batched, Big: 33 33 44 44
            0x33,
            0x33,
            0x44,
            0x44,
        )

    private val sandwichAbsentSample =
        SandwichBatch(
            a = 0x1111u,
            b = 0x2222u,
            gate = false,
            middle = null,
            c = 0x3333u,
            d = 0x4444u,
        )

    private val sandwichAbsentWire: ByteArray =
        byteArrayOf(
            0x11,
            0x11,
            0x22,
            0x22,
            0x00,
            0x33,
            0x33,
            0x44,
            0x44,
        )

    @Test
    fun sandwich_present_wireBytes_BigBuffer() {
        val buf =
            encodeIntoOrder(13, ByteOrder.BIG_ENDIAN) {
                SandwichBatchCodec.encode(it, sandwichPresentSample, EncodeContext.Empty)
            }
        assertContentEquals(sandwichPresentWire, buf.readByteArray(13))
    }

    @Test
    fun sandwich_present_wireBytes_LittleBuffer() {
        val buf =
            encodeIntoOrder(13, ByteOrder.LITTLE_ENDIAN) {
                SandwichBatchCodec.encode(it, sandwichPresentSample, EncodeContext.Empty)
            }
        assertContentEquals(
            sandwichPresentWire,
            buf.readByteArray(13),
            "wireOrder=Big must beat buffer.byteOrder=Little, sandwich shape",
        )
    }

    @Test
    fun sandwich_absent_wireBytes_BigBuffer() {
        val buf =
            encodeIntoOrder(9, ByteOrder.BIG_ENDIAN) {
                SandwichBatchCodec.encode(it, sandwichAbsentSample, EncodeContext.Empty)
            }
        assertContentEquals(sandwichAbsentWire, buf.readByteArray(9))
    }

    @Test
    fun sandwich_decode_present_BigBuffer() {
        val src = decodeBytes(sandwichPresentWire, ByteOrder.BIG_ENDIAN)
        assertEquals(sandwichPresentSample, SandwichBatchCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun sandwich_decode_present_LittleBuffer() {
        val src = decodeBytes(sandwichPresentWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(sandwichPresentSample, SandwichBatchCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun sandwich_decode_absent_BigBuffer() {
        val src = decodeBytes(sandwichAbsentWire, ByteOrder.BIG_ENDIAN)
        assertEquals(sandwichAbsentSample, SandwichBatchCodec.decode(src, DecodeContext.Empty))
    }

    // ─── @FramedBy + batching ────────────────────────────────────────────

    private val framedBatchSample =
        FramedBatchedBody(
            a = 0x1234u,
            b = 0x5678u,
            c = 0x9ABCDEF0u,
        )

    private val framedBatchBodyBytes: ByteArray =
        byteArrayOf(
            // a+b batched, Big: 12 34 56 78
            0x12,
            0x34,
            0x56,
            0x78,
            // c, Big: 9A BC DE F0
            0x9A.toByte(),
            0xBC.toByte(),
            0xDE.toByte(),
            0xF0.toByte(),
        )

    private val framedBatchWire: ByteArray =
        byteArrayOf(
            // Le32 length prefix: 8 bytes body, LE: 08 00 00 00
            0x08,
            0x00,
            0x00,
            0x00,
        ) + framedBatchBodyBytes

    @Test
    fun framedBatched_wireBytes_BigBuffer() {
        // FramedBatchedBody encode allocates a new buffer through factory,
        // so we use the codec's encode(value, context, factory) path via
        // the standalone-codec wrapper. Easiest: encode via the codec's
        // generated encode entry, which in framed mode returns a fresh
        // buffer rather than writing into a pre-allocated one.
        val readBuf =
            FramedBatchedBodyCodec.encode(
                value = framedBatchSample,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            )
        // The returned ReadBuffer is positioned at 0 ready to read.
        val actual = ByteArray(framedBatchWire.size) { readBuf.readByte() }
        assertContentEquals(framedBatchWire, actual)
    }

    @Test
    fun framedBatched_decode_BigBuffer() {
        val src = decodeBytes(framedBatchWire, ByteOrder.BIG_ENDIAN)
        assertEquals(framedBatchSample, FramedBatchedBodyCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun framedBatched_decode_LittleBuffer() {
        val src = decodeBytes(framedBatchWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(framedBatchSample, FramedBatchedBodyCodec.decode(src, DecodeContext.Empty))
    }

    // ─── Sealed dispatch with explicit-Big batchable variant body ───────

    private val typeASample =
        BigDispatchFrame.TypeA(
            a = 0x12u,
            b = 0x34u,
            c = 0x5678u,
            d = 0x9ABCDEF0u,
        )

    private val typeAWire: ByteArray =
        byteArrayOf(
            // dispatch discriminator
            0x01,
            // batched a+b+c = readInt, Big: 12 34 56 78
            0x12,
            0x34,
            0x56,
            0x78,
            // d single-scalar swap, Big: 9A BC DE F0
            0x9A.toByte(),
            0xBC.toByte(),
            0xDE.toByte(),
            0xF0.toByte(),
        )

    @Test
    fun bigDispatch_typeA_wireBytes_BigBuffer() {
        val buf =
            encodeIntoOrder(9, ByteOrder.BIG_ENDIAN) {
                BigDispatchFrameCodec.encode(it, typeASample, EncodeContext.Empty)
            }
        assertContentEquals(typeAWire, buf.readByteArray(9))
    }

    @Test
    fun bigDispatch_typeA_wireBytes_LittleBuffer() {
        val buf =
            encodeIntoOrder(9, ByteOrder.LITTLE_ENDIAN) {
                BigDispatchFrameCodec.encode(it, typeASample, EncodeContext.Empty)
            }
        assertContentEquals(
            typeAWire,
            buf.readByteArray(9),
            "sealed-variant explicit Big must beat buffer.byteOrder=Little",
        )
    }

    @Test
    fun bigDispatch_typeA_decode_BigBuffer() {
        val src = decodeBytes(typeAWire, ByteOrder.BIG_ENDIAN)
        assertEquals(typeASample, BigDispatchFrameCodec.decode(src, DecodeContext.Empty))
    }

    @Test
    fun bigDispatch_typeA_decode_LittleBuffer() {
        val src = decodeBytes(typeAWire, ByteOrder.LITTLE_ENDIAN)
        assertEquals(typeASample, BigDispatchFrameCodec.decode(src, DecodeContext.Empty))
    }
}
