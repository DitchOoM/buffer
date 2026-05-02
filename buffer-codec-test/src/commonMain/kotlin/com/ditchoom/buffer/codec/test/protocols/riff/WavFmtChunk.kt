package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * One full WAV `fmt ` chunk: the standard RIFF chunk header (4-byte
 * FourCC + 4-byte LE size) followed by a [WavFmtBody] of exactly
 * [chunkSize] bytes. The PCM-form body is fixed at 16 bytes; the WAV
 * spec also defines a 18- or 18+N-byte WAVEFORMATEX form, but for the
 * slice-4 vector we model the PCM case so the body type is fully typed.
 *
 * Slice 4 vector for `@LengthFrom` + `@UseCodec`. The body field is
 * delegated to [WavFmtBodyCodec] over a buffer that the parent codec
 * pre-bounds to exactly `chunkSize` bytes. The bound is defensive for
 * a fixed-size body — the body codec reads a known number of bytes —
 * but the bound + restore mechanism is the uniform contract for every
 * `@UseCodec` body inside a `@LengthFrom`-bounded field, and slice 4
 * exists to validate that contract.
 *
 * No `ReadBuffer` / `ByteArray` field appears on a `@ProtocolMessage`
 * data class anywhere in the slice — Section 8 of the design doctrine
 * forbids accidental raw-bytes payloads, and a typed body keeps the
 * `Payload` machinery (Stage H) out of slice 4's charter.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class WavFmtChunk(
    val fourCC: UInt,
    val chunkSize: UInt,
    @LengthFrom("chunkSize")
    @UseCodec(WavFmtBodyCodec::class)
    val body: WavFmtBody,
)

/**
 * The fields of a PCM WAV `fmt ` chunk. All six fields are stored
 * little-endian on the wire; the wire layout is fixed at 16 bytes:
 *
 *   audioFormat (2) | numChannels (2) | sampleRate (4) |
 *   byteRate    (4) | blockAlign  (2) | bitsPerSample (2)
 *
 * For PCM, `audioFormat` is `1`; for IEEE float it is `3`; for the
 * extension-bearing WAVEFORMATEX form it is `0xFFFE` and the wire form
 * is 18 bytes plus an N-byte extension (not modeled here — that case
 * would need either a second variant or a `Payload`-typed extension
 * slot).
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class WavFmtBody(
    val audioFormat: UShort,
    val numChannels: UShort,
    val sampleRate: UInt,
    val byteRate: UInt,
    val blockAlign: UShort,
    val bitsPerSample: UShort,
)
