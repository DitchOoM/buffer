package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pinning tests for [issue #154](https://github.com/DitchOoM/buffer/issues/154).
 *
 * Contract being asserted: `@ProtocolMessage(wireOrder = ...)` defines
 * the wire byte order absolutely. Every encode produces those bytes,
 * every decode consumes them, regardless of the runtime [ByteOrder] of
 * the `ReadBuffer` / `WriteBuffer` the codec is handed.
 *
 * Pre-fix these tests fail to **compile**: the emitter silently drops
 * signed-scalar fields with an explicit `wireOrder` and silently
 * rejects `Float`/`Double` entirely, so neither
 * `BigWirePacketCodec` nor `LittleWirePacketCodec` is generated and
 * the test source can't resolve them. After the fix the tests
 * compile, run, and pass — wire bytes match `expectedBigWireBytes` /
 * `expectedLittleWireBytes` regardless of buffer byte order, and
 * round-trips return the original values.
 */
class WireOrderMismatchTest {
    private val sample =
        BigWirePacket(
            bool = true,
            byte = 0x12.toByte(),
            ubyte = 0x9Au,
            short = 0x0102.toShort(),
            ushort = 0xCAFEu,
            int = 0x01020304,
            uint = 0xFFEEDDCCu,
            long = 0x0102030405060708L,
            ulong = 0xCAFEBABEDEADBEEFuL,
            float = Float.fromBits(0x40490FDB),
            double = Double.fromBits(0x400921FB54442D18L),
        )

    private val sampleLittle =
        LittleWirePacket(
            bool = true,
            byte = 0x12.toByte(),
            ubyte = 0x9Au,
            short = 0x0102.toShort(),
            ushort = 0xCAFEu,
            int = 0x01020304,
            uint = 0xFFEEDDCCu,
            long = 0x0102030405060708L,
            ulong = 0xCAFEBABEDEADBEEFuL,
            float = Float.fromBits(0x40490FDB),
            double = Double.fromBits(0x400921FB54442D18L),
        )

    private val expectedBigWireBytes: ByteArray =
        byteArrayOf(
            // bool true → 0x01
            0x01,
            // byte 0x12
            0x12,
            // ubyte 0x9A
            0x9A.toByte(),
            // short 0x0102
            0x01, 0x02,
            // ushort 0xCAFE
            0xCA.toByte(), 0xFE.toByte(),
            // int 0x01020304
            0x01, 0x02, 0x03, 0x04,
            // uint 0xFFEEDDCC
            0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(),
            // long 0x0102030405060708
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            // ulong 0xCAFEBABEDEADBEEF
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            // float 0x40490FDB (≈ π as Float)
            0x40, 0x49, 0x0F, 0xDB.toByte(),
            // double 0x400921FB54442D18 (≈ π as Double)
            0x40, 0x09, 0x21, 0xFB.toByte(), 0x54, 0x44, 0x2D, 0x18,
        )

    private val expectedLittleWireBytes: ByteArray = expectedBigWireBytes.reversedFieldwise()

    // ────────────────────────────────────────────────────────────
    // BigWirePacket — wireOrder = Big
    // ────────────────────────────────────────────────────────────

    @Test
    fun bigWire_encode_writesBigEndianBytes_evenIntoLittleEndianBuffer() {
        val buf = BufferFactory.Default.allocate(expectedBigWireBytes.size, ByteOrder.LITTLE_ENDIAN)
        BigWirePacketCodec.encode(buf, sample, EncodeContext.Empty)
        assertEquals(expectedBigWireBytes.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actual = buf.readByteArray(expectedBigWireBytes.size)
        assertContentEquals(
            expectedBigWireBytes,
            actual,
            "wireOrder=Big must beat buffer.byteOrder=Little — wire is BIG endian",
        )
    }

    @Test
    fun bigWire_decode_readsBigEndianBytes_evenFromLittleEndianBuffer() {
        val buf =
            BufferFactory.Default
                .allocate(expectedBigWireBytes.size, ByteOrder.LITTLE_ENDIAN)
                .also { it.writeBytes(expectedBigWireBytes) }
                .also { it.resetForRead() }
        val decoded = BigWirePacketCodec.decode(buf, DecodeContext.Empty)
        assertEquals(sample, decoded, "wireOrder=Big decode must yield the original values")
    }

    @Test
    fun bigWire_roundTrip_throughLittleEndianBuffer() {
        val buf = BufferFactory.Default.allocate(expectedBigWireBytes.size, ByteOrder.LITTLE_ENDIAN)
        BigWirePacketCodec.encode(buf, sample, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(sample, BigWirePacketCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun bigWire_roundTrip_throughBigEndianBuffer_baselineParity() {
        val buf = BufferFactory.Default.allocate(expectedBigWireBytes.size, ByteOrder.BIG_ENDIAN)
        BigWirePacketCodec.encode(buf, sample, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(sample, BigWirePacketCodec.decode(buf, DecodeContext.Empty))
    }

    // ────────────────────────────────────────────────────────────
    // LittleWirePacket — wireOrder = Little
    // ────────────────────────────────────────────────────────────

    @Test
    fun littleWire_encode_writesLittleEndianBytes_evenIntoBigEndianBuffer() {
        val buf = BufferFactory.Default.allocate(expectedLittleWireBytes.size, ByteOrder.BIG_ENDIAN)
        LittleWirePacketCodec.encode(buf, sampleLittle, EncodeContext.Empty)
        assertEquals(expectedLittleWireBytes.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actual = buf.readByteArray(expectedLittleWireBytes.size)
        assertContentEquals(
            expectedLittleWireBytes,
            actual,
            "wireOrder=Little must beat buffer.byteOrder=Big — wire is LITTLE endian",
        )
    }

    @Test
    fun littleWire_decode_readsLittleEndianBytes_evenFromBigEndianBuffer() {
        val buf =
            BufferFactory.Default
                .allocate(expectedLittleWireBytes.size, ByteOrder.BIG_ENDIAN)
                .also { it.writeBytes(expectedLittleWireBytes) }
                .also { it.resetForRead() }
        val decoded = LittleWirePacketCodec.decode(buf, DecodeContext.Empty)
        assertEquals(sampleLittle, decoded, "wireOrder=Little decode must yield the original values")
    }

    @Test
    fun littleWire_roundTrip_throughBigEndianBuffer() {
        val buf = BufferFactory.Default.allocate(expectedLittleWireBytes.size, ByteOrder.BIG_ENDIAN)
        LittleWirePacketCodec.encode(buf, sampleLittle, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(sampleLittle, LittleWirePacketCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun littleWire_roundTrip_throughLittleEndianBuffer_baselineParity() {
        val buf = BufferFactory.Default.allocate(expectedLittleWireBytes.size, ByteOrder.LITTLE_ENDIAN)
        LittleWirePacketCodec.encode(buf, sampleLittle, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(sampleLittle, LittleWirePacketCodec.decode(buf, DecodeContext.Empty))
    }

    // ────────────────────────────────────────────────────────────
    // BigWireFrame sealed dispatch — same fix surface inside a
    // @PacketType variant
    // ────────────────────────────────────────────────────────────

    @Test
    fun sealed_sampleVariant_roundTrip_throughLittleEndianBuffer() {
        val sampleVariant: BigWireFrame =
            BigWireFrame.Sample(
                short = 0x0102.toShort(),
                int = 0x01020304,
                long = 0x0102030405060708L,
                float = Float.fromBits(0x40490FDB),
                double = Double.fromBits(0x400921FB54442D18L),
            )
        val capacity = 1 + 2 + 4 + 8 + 4 + 8 // discriminator + sample fields
        val buf = BufferFactory.Default.allocate(capacity, ByteOrder.LITTLE_ENDIAN)
        BigWireFrameCodec.encode(buf, sampleVariant, EncodeContext.Empty)
        buf.resetForRead()
        // First byte must be the @PacketType discriminator regardless of buffer.byteOrder
        assertEquals(0x01.toByte(), buf.get(0), "discriminator byte for Sample")
        assertEquals(sampleVariant, BigWireFrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun sealed_statusVariant_roundTrip_throughLittleEndianBuffer() {
        val statusVariant: BigWireFrame =
            BigWireFrame.Status(
                flags = 0xCAFEBABEu,
                ratio = Double.fromBits(0x400921FB54442D18L),
            )
        val capacity = 1 + 4 + 8 // discriminator + flags + ratio
        val buf = BufferFactory.Default.allocate(capacity, ByteOrder.LITTLE_ENDIAN)
        BigWireFrameCodec.encode(buf, statusVariant, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x02.toByte(), buf.get(0), "discriminator byte for Status")
        assertEquals(statusVariant, BigWireFrameCodec.decode(buf, DecodeContext.Empty))
    }
}

/**
 * Reverses each field's bytes independently — produces the
 * little-endian wire layout from a big-endian wire layout for the
 * field shape declared by [BigWirePacket] / [LittleWirePacket]:
 * `bool, byte, ubyte, short, ushort, int, uint, long, ulong, float, double`.
 * 1-byte fields stay byte-identical; multi-byte fields reverse.
 */
private fun ByteArray.reversedFieldwise(): ByteArray {
    val widths = intArrayOf(1, 1, 1, 2, 2, 4, 4, 8, 8, 4, 8)
    val out = ByteArray(size)
    var off = 0
    for (w in widths) {
        for (i in 0 until w) out[off + i] = this[off + (w - 1 - i)]
        off += w
    }
    require(off == size) { "field widths sum mismatch: $off vs $size" }
    return out
}
