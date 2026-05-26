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
}
