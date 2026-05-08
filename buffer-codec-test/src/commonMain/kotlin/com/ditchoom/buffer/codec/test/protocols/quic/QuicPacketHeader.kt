package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Slice — Boolean-returning `@DispatchValue` vector.
 *
 * QUIC long-header form bit (RFC 9000 §17.2 / §17.3). The first byte
 * of every QUIC packet carries a `Header Form` bit at position 7 (the
 * high bit). When set, the packet is a long-header packet (§17.2 —
 * Initial / 0-RTT / Handshake / Retry); when clear, it's a
 * short-header (1-RTT) packet (§17.3). The `Fixed Bit` at position 6
 * is set to 1 in both forms (a 0 here is a malformed packet per §17).
 *
 * Slice widens `@DispatchValue` return types beyond `Int` to
 * include `Boolean`. The dispatcher emits an inline 0/1 lift
 * (`if (isLongHeader) 1 else 0`) at the dispatch site so the
 * `when (__dispatchValue)` branches stay Int-typed; variants declare
 * `@PacketType(value = 0)` for short-header and `@PacketType(value =
 * 1)` for long-header. The validator constrains `@PacketType.value` to
 * 0..1 for Boolean returns; values outside that range are a focused
 * compile error.
 */
@JvmInline
@ProtocolMessage
value class QuicHeaderByte(
    val raw: UByte,
) {
    @DispatchValue
    val isLongHeader: Boolean get() = (raw.toUInt() and 0x80u) != 0u
}

@DispatchOn(QuicHeaderByte::class)
@ProtocolMessage
sealed interface QuicPacketHeader {
    /** RFC 9000 §17.3 — short-header (1-RTT) packet. Form bit 0, fixed bit 1 → 0x40. */
    @PacketType(value = 0)
    @ProtocolMessage
    data class ShortHeader(
        val firstByte: QuicHeaderByte = QuicHeaderByte(0x40u),
    ) : QuicPacketHeader

    /** RFC 9000 §17.2 — long-header packet (Initial / 0-RTT / Handshake / Retry). Form bit 1, fixed bit 1 → 0xC0. */
    @PacketType(value = 1)
    @ProtocolMessage
    data class LongHeader(
        val firstByte: QuicHeaderByte = QuicHeaderByte(0xC0u),
    ) : QuicPacketHeader
}
