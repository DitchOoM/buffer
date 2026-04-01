package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
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
 * The length field comes BEFORE the type on the wire. This is handled by using
 * a data class discriminator [PngChunkHeader] that reads both length + type,
 * then dispatches on type. Each variant includes the header as a field — the
 * processor detects the matching type and populates it from context during decode,
 * writes it normally during encode.
 *
 * CRC is modeled as a read/write UInt field — CRC validation is application-level.
 */

/** PNG chunk type constants as 4-byte big-endian integers. */
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
 * PNG chunk header: length (4 bytes) + type (4 bytes).
 * Read as the @DispatchOn discriminator — dispatches on the type field.
 */
@ProtocolMessage
data class PngChunkHeader(
    val length: UInt,
    val type: UInt,
) {
    @DispatchValue
    val chunkType: Int get() = type.toInt()
}

/**
 * PNG chunks dispatched by the type field in the 8-byte header.
 * Each variant includes the header — populated from context during decode,
 * written normally during encode.
 *
 * Models fixed-structure chunks. Variable-length data chunks (IDAT, tEXt) use
 * [PngDataChunk] directly since @LengthFrom cannot reference dotted paths
 * into the header.
 */
@DispatchOn(PngChunkHeader::class)
@ProtocolMessage
sealed interface PngChunk {
    /**
     * IHDR chunk (PNG §11.2.2) — image header, must be first chunk.
     * Length is always 13. Data: width + height + bitDepth + colorType + 3 methods.
     */
    @PacketType(0x49484452)
    @ProtocolMessage
    data class Ihdr(
        val header: PngChunkHeader,
        val width: UInt,
        val height: UInt,
        val bitDepth: UByte,
        val colorType: UByte,
        val compressionMethod: UByte,
        val filterMethod: UByte,
        val interlaceMethod: UByte,
        val crc: UInt,
    ) : PngChunk

    /**
     * IEND chunk (PNG §11.2.5) — image trailer, must be last chunk.
     * Length is always 0, no data, only CRC.
     */
    @PacketType(0x49454E44)
    @ProtocolMessage
    data class Iend(
        val header: PngChunkHeader,
        val crc: UInt,
    ) : PngChunk
}

/**
 * Generic PNG data chunk — for IDAT, tEXt, or any chunk with variable-length data.
 * Standalone model (not in sealed dispatch) since the payload length comes from
 * a header field that @LengthFrom can reference directly.
 *
 * Wire: length(4) + type(4) + data(length) + crc(4)
 */
@ProtocolMessage
data class PngDataChunk<@Payload P>(
    val length: UInt,
    val type: UInt,
    @LengthFrom("length") val data: P,
    val crc: UInt,
)
