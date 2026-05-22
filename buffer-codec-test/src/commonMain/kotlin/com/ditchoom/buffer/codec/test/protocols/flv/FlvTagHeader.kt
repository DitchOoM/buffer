package com.ditchoom.buffer.codec.test.protocols.flv

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireBytes

/**
 * FLV tag header — Adobe Flash Video File Format Specification v10.1,
 * section "FLV tag" (E.4.1). The 11 bytes preceding every FLV tag's
 * data: a 1-byte tag-type, three 24-bit big-endian length/timestamp
 * fields, an 8-bit timestamp-extended high byte, and a 24-bit big-
 * endian stream id (always zero per spec).
 *
 * ```text
 * +---------------+---------------+---------------+---------------+
 * |   tagType (1) |          dataSize (3 BE)                      |
 * +---------------+---------------+---------------+---------------+
 * |          timestamp (3 BE)     | tsExt (1)     |               |
 * +---------------+---------------+---------------+---------------+
 * |          streamId (3 BE)                      |
 * +---------------+---------------+---------------+
 * ```
 *
 * Vector for `@WireBytes(N)` where the wire width is narrower
 * than the Kotlin type's natural size. Three independent 24-bit BE
 * fields land inside an 11-byte structure that cannot collapse into a
 * single 64-bit packed scalar — `@WireBytes(3)` genuinely earns its
 * keep here, unlike fixed bit-packed headers where a value-class-of-
 * raw shape is cleaner (see [com.ditchoom.buffer.codec.test.protocols.mysql.MySqlPacketHeader]).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class FlvTagHeader(
    val tagType: UByte,
    @WireBytes(3) val dataSize: UInt,
    @WireBytes(3) val timestamp: UInt,
    val timestampExtended: UByte,
    @WireBytes(3) val streamId: UInt,
)
