package com.ditchoom.buffer.codec.test.protocols.tcp

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Slice — UByte-returning `@DispatchValue` vector.
 *
 * TCP control-bits byte (RFC 793 §3.1, byte offset 13 of the TCP
 * header). The 8-bit field carries (high to low) `CWR ECE URG ACK PSH
 * RST SYN FIN`. Specific flag combinations name the segment kind in
 * the connection state machine: `SYN` opens a connection, `SYN+ACK`
 * acknowledges the open from the listener side, `ACK` is a steady-
 * state data-or-empty acknowledgement, `FIN+ACK` requests connection
 * close, and `RST` aborts. Modeling these as sealed variants of
 * `TcpSegmentByFlags` is a useful test fixture rather than a faithful
 * TCP implementation — real TCP carries arbitrary flag combinations
 * inside a single packet shape, not a sealed family.
 *
 * Slice widens `@DispatchValue` return types to include
 * `UByte`. The dispatcher emits a `.toInt()` coercion at the dispatch
 * site so the `when (__dispatchValue)` branches stay Int-typed;
 * variants declare `@PacketType(value = N)` with `N` interpreted as
 * the unsigned byte value. The validator restricts `@PacketType.value`
 * to 0..255 for UByte returns; values outside that range are a
 * focused compile error.
 */
@JvmInline
@ProtocolMessage
value class TcpFlagsByte(
    val raw: UByte,
) {
    @DispatchValue
    val flags: UByte get() = raw
}

@DispatchOn(TcpFlagsByte::class)
@ProtocolMessage
sealed interface TcpSegmentByFlags {
    /** SYN-only — RFC 793 §3.4 connection establishment, first segment. */
    @PacketType(value = 0x02)
    @ProtocolMessage
    data class Syn(
        val flags: TcpFlagsByte = TcpFlagsByte(0x02u),
    ) : TcpSegmentByFlags

    /** SYN+ACK — RFC 793 §3.4 connection establishment, listener response. */
    @PacketType(value = 0x12)
    @ProtocolMessage
    data class SynAck(
        val flags: TcpFlagsByte = TcpFlagsByte(0x12u),
    ) : TcpSegmentByFlags

    /** ACK-only — steady-state acknowledgement (no data, or empty payload). */
    @PacketType(value = 0x10)
    @ProtocolMessage
    data class Ack(
        val flags: TcpFlagsByte = TcpFlagsByte(0x10u),
    ) : TcpSegmentByFlags

    /** FIN+ACK — RFC 793 §3.5 connection termination request. */
    @PacketType(value = 0x11)
    @ProtocolMessage
    data class FinAck(
        val flags: TcpFlagsByte = TcpFlagsByte(0x11u),
    ) : TcpSegmentByFlags

    /** RST — RFC 793 §3.4 connection abort. */
    @PacketType(value = 0x04)
    @ProtocolMessage
    data class Rst(
        val flags: TcpFlagsByte = TcpFlagsByte(0x04u),
    ) : TcpSegmentByFlags
}
