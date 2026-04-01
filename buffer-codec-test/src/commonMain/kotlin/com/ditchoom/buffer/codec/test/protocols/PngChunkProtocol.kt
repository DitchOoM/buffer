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
 * uint32 length;     // 4 bytes big-endian (data only, excludes type and CRC)
 * uint32 type;       // 4 bytes ASCII (e.g., IHDR, IDAT, IEND)
 * byte   data[length];
 * uint32 crc;        // 4 bytes CRC-32 over type + data
 * ```
 *
 * 4-byte Int @DispatchOn — exercises multi-byte discriminator support.
 * CRC is modeled as a read/write UInt field (validation is application-level).
 */

/** PNG chunk type as a 4-byte big-endian integer. */
@JvmInline
@ProtocolMessage
value class PngChunkType(val raw: UInt) {
    @DispatchValue
    val type: Int get() = raw.toInt()

    companion object {
        // ASCII "IHDR" = 0x49484452
        val IHDR = PngChunkType(0x49484452u)
        // ASCII "PLTE" = 0x504C5445
        val PLTE = PngChunkType(0x504C5445u)
        // ASCII "IDAT" = 0x49444154
        val IDAT = PngChunkType(0x49444154u)
        // ASCII "IEND" = 0x49454E44
        val IEND = PngChunkType(0x49454E44u)
        // ASCII "tEXt" = 0x74455874
        val tEXt = PngChunkType(0x74455874u)
    }
}

/**
 * PNG chunk sealed interface dispatched by 4-byte chunk type.
 *
 * Models a subset of critical PNG chunks. Each variant's wire value
 * is the 4-byte ASCII chunk type encoded as a big-endian Int.
 */
@DispatchOn(PngChunkType::class)
@ProtocolMessage
sealed interface PngChunk {
    /**
     * IHDR chunk (PNG §11.2.2) — image header, must be first chunk.
     * 13 bytes of data + 4 bytes CRC.
     */
    @PacketType(value = 0x49484452, wire = 0x49484452)
    @ProtocolMessage
    data class Ihdr(
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
     * Zero bytes of data, only CRC.
     */
    @PacketType(value = 0x49454E44, wire = 0x49454E44)
    @ProtocolMessage
    @JvmInline
    value class Iend(
        val crc: UInt,
    ) : PngChunk

    /**
     * tEXt chunk (PNG §11.3.4.3) — uncompressed text metadata.
     * Keyword + null separator + text.
     */
    @PacketType(value = 0x74455874, wire = 0x74455874)
    @ProtocolMessage
    data class Text<@Payload P>(
        val dataLength: UInt,
        @LengthFrom("dataLength") val textData: P,
        val crc: UInt,
    ) : PngChunk
}
