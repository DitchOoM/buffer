package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
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
 * All variant fields inherit little-endian from the sealed interface.
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
        val FMT = RiffChunkId(0x666D7420u) // "fmt "
        val DATA = RiffChunkId(0x64617461u) // "data"
        val FACT = RiffChunkId(0x66616374u) // "fact"
    }
}

/**
 * RIFF sub-chunks dispatched by 4-byte ASCII chunk ID.
 * All variants inherit little-endian byte order from the sealed interface.
 */
@DispatchOn(RiffChunkId::class)
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface RiffChunk {
    /** "fmt " chunk — WAV audio format descriptor (16-byte PCM). */
    @PacketType(value = 0x666D7420, wire = 0x666D7420)
    @ProtocolMessage
    data class Fmt(
        val chunkSize: UInt,
        val audioFormat: UShort,
        val numChannels: UShort,
        val sampleRate: UInt,
        val byteRate: UInt,
        val blockAlign: UShort,
        val bitsPerSample: UShort,
    ) : RiffChunk

    /** "fact" chunk — sample count for compressed formats. */
    @PacketType(value = 0x66616374, wire = 0x66616374)
    @ProtocolMessage
    data class Fact(
        val chunkSize: UInt,
        val sampleCount: UInt,
    ) : RiffChunk

    /** "data" chunk — raw audio samples. */
    @PacketType(value = 0x64617461, wire = 0x64617461)
    @ProtocolMessage
    data class Data<@Payload P>(
        val chunkSize: UInt,
        @LengthFrom("chunkSize") val samples: P,
    ) : RiffChunk
}
