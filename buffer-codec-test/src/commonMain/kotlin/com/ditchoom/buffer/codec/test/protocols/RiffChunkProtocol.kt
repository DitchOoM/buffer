package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireOrder
import kotlin.jvm.JvmInline

/*
 * RIFF container format (used by WAV, AVI, WebP).
 *
 * Wire format:
 * ```
 * uint32 chunkId;       // 4 bytes ASCII, big-endian (dispatch discriminator)
 * uint32 chunkSize;     // 4 bytes little-endian
 * byte   data[chunkSize];
 * ```
 *
 * Chunk IDs are 4-byte ASCII stored as big-endian UInt (buffer default).
 * Size and numeric fields use @WireOrder(LITTLE_ENDIAN) for spec-compliant byte order.
 * Pad byte for odd-length chunks is a structural concern not modeled here.
 */

/** RIFF chunk ID as a 4-byte big-endian integer. */
@JvmInline
@ProtocolMessage
value class RiffChunkId(
    val raw: UInt,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()

    companion object {
        // "fmt " = 0x666D7420
        val FMT = RiffChunkId(0x666D7420u)

        // "data" = 0x64617461
        val DATA = RiffChunkId(0x64617461u)

        // "fact" = 0x66616374
        val FACT = RiffChunkId(0x66616374u)
    }
}

/**
 * RIFF sub-chunks dispatched by 4-byte ASCII chunk ID.
 * Models WAV-specific chunks as a representative example.
 */
@DispatchOn(RiffChunkId::class)
@ProtocolMessage
sealed interface RiffChunk {
    /**
     * "fmt " chunk — WAV audio format descriptor.
     * Fixed 16-byte PCM format (no extra params).
     */
    @PacketType(value = 0x666D7420, wire = 0x666D7420)
    @ProtocolMessage
    data class Fmt(
        @WireOrder(Endianness.LITTLE_ENDIAN) val chunkSize: UInt, // always 16 for PCM
        @WireOrder(Endianness.LITTLE_ENDIAN) val audioFormat: UShort, // 1 = PCM
        @WireOrder(Endianness.LITTLE_ENDIAN) val numChannels: UShort,
        @WireOrder(Endianness.LITTLE_ENDIAN) val sampleRate: UInt,
        @WireOrder(Endianness.LITTLE_ENDIAN) val byteRate: UInt,
        @WireOrder(Endianness.LITTLE_ENDIAN) val blockAlign: UShort,
        @WireOrder(Endianness.LITTLE_ENDIAN) val bitsPerSample: UShort,
    ) : RiffChunk

    /**
     * "fact" chunk — sample count for compressed formats.
     */
    @PacketType(value = 0x66616374, wire = 0x66616374)
    @ProtocolMessage
    data class Fact(
        @WireOrder(Endianness.LITTLE_ENDIAN) val chunkSize: UInt,
        @WireOrder(Endianness.LITTLE_ENDIAN) val sampleCount: UInt,
    ) : RiffChunk

    /**
     * "data" chunk — raw audio samples.
     */
    @PacketType(value = 0x64617461, wire = 0x64617461)
    @ProtocolMessage
    data class Data<@Payload P>(
        @WireOrder(Endianness.LITTLE_ENDIAN) val chunkSize: UInt,
        @LengthFrom("chunkSize") val samples: P,
    ) : RiffChunk
}
