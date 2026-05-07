package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #151 part 2 (J.M.6.c) — non-terminal `@RemainingBytes` round-trip.
 *
 * The PNG fixture's `@RemainingBytes val data` sits before a fixed
 * 4-byte CRC trailer. Without J.M.6.c the body loop would consume the
 * CRC bytes (loop bound `position < limit`) and the trailer's
 * `readUInt` would underflow. With J.M.6.c the analyzer detects the
 * fixed trailer (Scalar `crc: UInt` → 4 wire bytes) and emits the
 * loop with `position < limit - 4` so the CRC survives intact.
 */
class PngChunkRoundTripTest {
    @Test
    fun pngChunkRoundTripsAcrossNonTerminalBody() {
        // type "IDAT" = 0x49444154 (per PNG spec §11.2.4)
        val data = listOf<UByte>(0x78u, 0x9Cu, 0x63u, 0x60u, 0x60u, 0x60u, 0x00u, 0x00u, 0x00u, 0x05u, 0x00u, 0x01u)
        val original =
            PngChunk(
                length = data.size.toUInt(),
                type = 0x49444154u,
                data = data,
                crc = 0xDEADBEEFu,
            )
        val totalBytes = 4 + 4 + data.size + 4
        val decoded = roundTripPngChunk(original, totalBytes)
        assertEquals(original, decoded)
    }

    @Test
    fun pngChunkWithEmptyDataRoundTrips() {
        // IEND chunk has 0 data bytes; layout is still length:4 + type:4 + (no data) + crc:4 = 12.
        val original =
            PngChunk(
                length = 0u,
                type = 0x49454E44u, // "IEND"
                data = emptyList(),
                crc = 0xAE426082u,
            )
        val decoded = roundTripPngChunk(original, totalBytes = 12)
        assertEquals(original, decoded)
        assertEquals(0, decoded.data.size)
    }

    @Test
    fun pngChunkBodyStopsBeforeCrcAndCrcSurvives() {
        // Build a chunk whose data contains bytes that look CRC-like to
        // confirm the body loop's "position < limit - 4" bound is honoring
        // limit, not reading until the buffer end.
        val data = listOf<UByte>(0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu)
        val original =
            PngChunk(
                length = data.size.toUInt(),
                type = 0x494D4147u, // "IMAG" (made up)
                data = data,
                crc = 0x12345678u,
            )
        val totalBytes = 4 + 4 + data.size + 4
        val decoded = roundTripPngChunk(original, totalBytes)
        assertEquals(8, decoded.data.size, "body must read exactly data.size bytes — CRC stays in trailer")
        assertEquals(0x12345678u, decoded.crc, "CRC must survive the body loop's bound")
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeCollapsesToBackPatchForNonTerminalBody() {
        // Non-terminal @RemainingBytes collapses parent wireSize to
        // BackPatch (mirror of @LengthPrefixed val: String / @RemainingBytes
        // val: String). Pinning here so a future "compute exact size"
        // change is a deliberate doctrine update, not a silent regression.
        val chunk =
            PngChunk(
                length = 4u,
                type = 0x49444154u,
                data = listOf(0u, 1u, 2u, 3u),
                crc = 0xCAFEBABEu,
            )
        assertEquals(WireSize.BackPatch, PngChunkCodec.wireSize(chunk, EncodeContext.Empty))
    }

    private fun roundTripPngChunk(
        sample: PngChunk,
        totalBytes: Int,
    ): PngChunk {
        val buf = BufferFactory.Default.allocate(totalBytes + 16, ByteOrder.BIG_ENDIAN)
        PngChunkCodec.encode(buf, sample, EncodeContext.Empty)
        assertEquals(totalBytes, buf.position(), "encoded byte count")
        // Bound the buffer to the chunk's extent — outer protocol would
        // size this from the leading length field; for the test we know
        // the exact byte count.
        val end = buf.position()
        buf.position(0)
        buf.setLimit(end)
        return PngChunkCodec.decode(buf, DecodeContext.Empty)
    }
}
