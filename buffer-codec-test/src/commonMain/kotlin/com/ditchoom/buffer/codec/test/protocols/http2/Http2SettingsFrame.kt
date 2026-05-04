package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireBytes

/**
 * HTTP/2 SETTINGS parameter per RFC 7540 §6.5.1 — a single
 * 6-byte entry: 16-bit identifier + 32-bit value, big-endian.
 *
 * Standard identifiers per RFC 7540 §6.5.2:
 *   - 0x1 SETTINGS_HEADER_TABLE_SIZE
 *   - 0x2 SETTINGS_ENABLE_PUSH
 *   - 0x3 SETTINGS_MAX_CONCURRENT_STREAMS
 *   - 0x4 SETTINGS_INITIAL_WINDOW_SIZE
 *   - 0x5 SETTINGS_MAX_FRAME_SIZE
 *   - 0x6 SETTINGS_MAX_HEADER_LIST_SIZE
 *
 * Stage G slice 7a element type — the slice 7a vector
 * (`Http2SettingsFrame`) carries a `List<Http2Setting>` whose byte
 * count is determined by the frame header's `length` field via
 * `@LengthFrom`.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class Http2Setting(
    val identifier: UShort,
    val value: UInt,
)

/**
 * HTTP/2 SETTINGS frame per RFC 7540 §6.5 — payload is a sequence
 * of 6-byte settings entries, count derived from the frame's
 * `length` field divided by 6.
 *
 * Stage G slice 7a doctrine vector — exercises `@LengthFrom` on
 * `List<@ProtocolMessage>`. Standalone codec; integrating into the
 * `Http2Frame` dispatcher (slice 6.5) requires `@LengthFrom`
 * dotted-form (`@LengthFrom("header.length")`) which is a future
 * widening. This fixture splits length and type into separate
 * scalar fields so the simple-name `@LengthFrom("length")` works.
 *
 * Wire layout per RFC 7540 §4.1 + §6.5:
 *
 * ```text
 *   +-----------------------------------------------+
 *   |             length (24 bits, BE)              |   3 bytes
 *   +---------------+-------------------------------+
 *   |   type (8)    | flags (8) |
 *   +---------------+-----------+-------------------+
 *   |R|             streamId (31 bits, BE)          |   4 bytes
 *   +-+---------------------------------------------+
 *   |       SETTINGS entries (length bytes)         |
 *   |   each: identifier (16) | value (32)          |
 *   +-----------------------------------------------+
 * ```
 *
 * The codec trusts the user to keep `length == entries.size * 6`
 * (per spec each entry is exactly 6 bytes); a runtime cross-check
 * would not be in slice 7a's scope.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class Http2SettingsFrame(
    @WireBytes(3) val length: UInt,
    val type: UByte,
    val flags: UByte,
    val streamId: Http2StreamId,
    @LengthFrom("length") val entries: List<Http2Setting>,
)
