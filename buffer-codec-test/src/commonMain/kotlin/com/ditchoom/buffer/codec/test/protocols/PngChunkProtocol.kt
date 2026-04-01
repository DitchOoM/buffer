package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * PNG chunk format (PNG Specification §5.3).
 *
 * Wire format:
 * ```
 * uint32 length;     // 4 bytes big-endian (data bytes only, excludes type and CRC)
 * char   type[4];    // 4 bytes ASCII (e.g., IHDR, IDAT, IEND)
 * byte   data[length];
 * uint32 crc;        // 4 bytes CRC-32 over type + data
 * ```
 *
 * NOTE: @DispatchOn cannot be used here because the length field comes BEFORE
 * the chunk type on the wire. @DispatchOn reads the discriminator first, but
 * PNG requires reading length first, then type. Instead, each chunk type is
 * modeled as a standalone @ProtocolMessage with explicit type + length fields.
 *
 * CRC is modeled as a read/write UInt field — CRC validation is application-level.
 */

/** PNG chunk type as a 4-byte big-endian integer. */
@JvmInline
value class PngChunkType(val raw: UInt) {
    companion object {
        val IHDR = PngChunkType(0x49484452u) // "IHDR"
        val PLTE = PngChunkType(0x504C5445u) // "PLTE"
        val IDAT = PngChunkType(0x49444154u) // "IDAT"
        val IEND = PngChunkType(0x49454E44u) // "IEND"
        val tEXt = PngChunkType(0x74455874u) // "tEXt"
    }
}

/**
 * IHDR chunk (PNG §11.2.2) — image header, must be first chunk.
 * Wire: 00 00 00 0D 49 48 44 52 [13 bytes data] [4 bytes CRC]
 * Length is always 13 (width + height + bitDepth + colorType + compression + filter + interlace).
 */
@ProtocolMessage
data class PngIhdrChunk(
    val length: UInt, // always 13
    val type: UInt, // always 0x49484452 ("IHDR")
    val width: UInt,
    val height: UInt,
    val bitDepth: UByte,
    val colorType: UByte,
    val compressionMethod: UByte,
    val filterMethod: UByte,
    val interlaceMethod: UByte,
    val crc: UInt,
)

/**
 * IEND chunk (PNG §11.2.5) — image trailer, must be last chunk.
 * Wire: 00 00 00 00 49 45 4E 44 [4 bytes CRC]
 * Length is always 0 (no data).
 */
@ProtocolMessage
data class PngIendChunk(
    val length: UInt, // always 0
    val type: UInt, // always 0x49454E44 ("IEND")
    val crc: UInt,
)

/**
 * Generic PNG data chunk — for IDAT, tEXt, or any chunk with variable-length data.
 * Wire: [4 bytes length] [4 bytes type] [length bytes data] [4 bytes CRC]
 */
@ProtocolMessage
data class PngDataChunk<@Payload P>(
    val length: UInt,
    val type: UInt,
    @LengthFrom("length") val data: P,
    val crc: UInt,
)
