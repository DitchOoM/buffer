package com.ditchoom.buffer.codec.test.protocols.slice7c

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Phase J.M.0 doctrine vector. Validates `@RemainingBytes` on
 * `List<@ProtocolMessage T>`: the body's repeated nested-message
 * elements read until the buffer's limit is reached. Caller-bounded
 * — the test sets `buffer.setLimit` before calling decode to simulate
 * the outer framing layer (e.g., MQTT's fixed-header remaining-length
 * variable-length integer).
 *
 * Element wire = 3 bytes (UShort BE blockId + UByte blockKind);
 * message wire = 2 bytes (streamId) + 3 × N (blocks).
 */
class RepeatedBlocksCodecTest {
    @Test
    fun encodesEmptyBlockListByteExact() {
        val msg = RepeatedBlocks(streamId = 0x0001u, blocks = emptyList())
        val expected =
            byteArrayOf(
                0x00,
                0x01, // stream id
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesThreeBlocksByteExact() {
        val msg =
            RepeatedBlocks(
                streamId = 0x1234u,
                blocks =
                    listOf(
                        RepeatedBlock(blockId = 0x0010u, blockKind = 0x00u),
                        RepeatedBlock(blockId = 0x0020u, blockKind = 0x01u),
                        RepeatedBlock(blockId = 0x0030u, blockKind = 0x80u),
                    ),
            )
        val expected =
            byteArrayOf(
                0x12,
                0x34, // stream id
                0x00,
                0x10,
                0x00, // block 0
                0x00,
                0x20,
                0x01, // block 1
                0x00,
                0x30,
                0x80.toByte(), // block 2
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesBlocksUntilBufferLimit() {
        // Wire: stream id (2 bytes) + 3 blocks (9 bytes) = 11 bytes total.
        // Allocate exactly 11; the entire buffer is the body region.
        val buf = BufferFactory.Default.allocate(11, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x1234.toShort())
        buf.writeShort(0x0010.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeShort(0x0020.toShort())
        buf.writeByte(0x01.toByte())
        buf.writeShort(0x0030.toShort())
        buf.writeByte(0x80.toByte())
        buf.resetForRead()

        val decoded = RepeatedBlocksCodec.decode(buf, DecodeContext.Empty)
        assertEquals(0x1234u.toUShort(), decoded.streamId)
        assertEquals(
            listOf(
                RepeatedBlock(blockId = 0x0010u, blockKind = 0x00u),
                RepeatedBlock(blockId = 0x0020u, blockKind = 0x01u),
                RepeatedBlock(blockId = 0x0030u, blockKind = 0x80u),
            ),
            decoded.blocks,
        )
    }

    @Test
    fun decodeRespectsExternallySetLimit() {
        // Wire allocated as 14 bytes but only the first 11 are the
        // body (caller has set limit accordingly). Trailing bytes
        // simulate a following packet — must not be consumed.
        val buf = BufferFactory.Default.allocate(14, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0xABCD.toShort())
        buf.writeShort(0x0001.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeShort(0x0002.toShort())
        buf.writeByte(0x01.toByte())
        buf.writeShort(0x0003.toShort())
        buf.writeByte(0x02.toByte())
        // Trailing bytes — start of next packet, must remain unconsumed.
        buf.writeByte(0xDE.toByte())
        buf.writeByte(0xAD.toByte())
        buf.writeByte(0xBE.toByte())
        buf.resetForRead()
        // Outer framing: limit body to 11 bytes (= 2 + 3 × 3).
        buf.setLimit(11)

        val decoded = RepeatedBlocksCodec.decode(buf, DecodeContext.Empty)
        assertEquals(3, decoded.blocks.size, "list bounded by external limit, not buffer end")
        assertEquals(11, buf.position(), "decode advanced position to the bounded limit")
    }

    @Test
    fun roundTripsTwoBlocks() {
        val original =
            RepeatedBlocks(
                streamId = 0xABCDu,
                blocks =
                    listOf(
                        RepeatedBlock(blockId = 0x0100u, blockKind = 0x00u),
                        RepeatedBlock(blockId = 0x0200u, blockKind = 0x02u),
                    ),
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = RepeatedBlocksCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsExactSumOfElementWireSizes() {
        // 2 bytes (stream id) + 3 × N bytes (each block is UShort+UByte).
        val three =
            RepeatedBlocks(
                streamId = 1u,
                blocks =
                    listOf(
                        RepeatedBlock(blockId = 0u, blockKind = 0u),
                        RepeatedBlock(blockId = 1u, blockKind = 1u),
                        RepeatedBlock(blockId = 2u, blockKind = 2u),
                    ),
            )
        assertEquals(WireSize.Exact(11), RepeatedBlocksCodec.wireSize(three, EncodeContext.Empty))

        val empty = RepeatedBlocks(streamId = 1u, blocks = emptyList())
        assertEquals(WireSize.Exact(2), RepeatedBlocksCodec.wireSize(empty, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeReportsNoFraming() {
        // @RemainingBytes signals "outer framing required" — peek
        // can't determine the size without the caller-set buffer
        // limit, which the stream-side peek doesn't see.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NoFraming, RepeatedBlocksCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: RepeatedBlocks,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches Phase J.M.0 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match expected wire layout")
    }

    private fun encode(value: RepeatedBlocks) =
        BufferFactory.Default
            .allocate(64, ByteOrder.BIG_ENDIAN)
            .also { RepeatedBlocksCodec.encode(it, value, EncodeContext.Empty) }
}
