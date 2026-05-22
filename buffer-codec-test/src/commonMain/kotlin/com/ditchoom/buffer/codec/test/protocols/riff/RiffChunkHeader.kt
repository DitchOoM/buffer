package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireOrder

/**
 * RIFF chunk header — 4-byte ASCII FourCC tag followed by a 4-byte
 * little-endian unsigned size. Independent of the body that follows
 * it on the wire; see [WavFmtChunk] for the framed view that reads
 * `chunkSize` bytes of body after the header.
 *
 * Slice 1 vector for `@ProtocolMessage`. Validates: fixed-layout
 * two-field message with mixed effective endianness (FourCC bytes
 * are positional ASCII — `@WireOrder(Endianness.Big)` makes the
 * MSB-first wire serialization explicit so the emitter has a
 * faithful directive; `chunkSize` is little-endian per the RIFF 1.0
 * spec, inheriting the message-level `wireOrder`).
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class RiffChunkHeader(
    @WireOrder(Endianness.Big) val fourCC: UInt,
    val chunkSize: UInt,
)
