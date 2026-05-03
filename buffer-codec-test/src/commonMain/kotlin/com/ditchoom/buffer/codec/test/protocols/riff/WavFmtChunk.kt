package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * One full WAV `fmt ` chunk: a 4-byte FourCC followed by a length-
 * prefixed [WavFmtBody]. The 4-byte LE prefix carries the body's wire
 * size; the PCM-form body is fixed at 16 bytes, so a typical PCM `fmt `
 * chunk is 24 bytes on the wire (4 fourCC + 4 prefix + 16 body).
 *
 * Slice 4 vector for `@LengthPrefixed` on a `@ProtocolMessage` field
 * (R3 widening). Encode emits the prefix carrying `WavFmtBodyCodec`'s
 * `wireSize`; decode reads the prefix, `setLimit`-bounds, decodes the
 * body, and restores the outer limit so a chunk embedded inside a RIFF
 * LIST stays composable.
 *
 * The `chunkSize` field is no longer a constructor parameter — its sole
 * purpose was to bound the body, which `@LengthPrefixed` now expresses
 * directly. Wire-mirroring carve-out: redundant length carriers are not
 * constructor parameters; checksums, magic numbers, and padding still
 * are.
 *
 * No `ReadBuffer` / `ByteArray` field appears on a `@ProtocolMessage`
 * data class anywhere in the slice — Section 8 of the design doctrine
 * forbids accidental raw-bytes payloads, and a typed body keeps the
 * `Payload` machinery (Stage H) out of slice 4's charter.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class WavFmtChunk(
    val fourCC: UInt,
    @LengthPrefixed(LengthPrefix.Int)
    val body: WavFmtBody,
)

/**
 * The fields of a PCM WAV `fmt ` body. All six fields are stored
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
