package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Slice — UShort-returning `@DispatchValue` vector.
 *
 * Ethernet `EtherType` field (IEEE 802.3) — a 16-bit big-endian value
 * at offset 12 of an Ethernet II frame that names the upper-layer
 * protocol carried in the payload. Common values:
 *
 *   - 0x0800 — IPv4 (RFC 894)
 *   - 0x0806 — ARP (RFC 826)
 *   - 0x86DD — IPv6 (RFC 8200)
 *   - 0x8100 — IEEE 802.1Q VLAN tag
 *
 * Slice widens `@DispatchValue` return types to include
 * `UShort`. The dispatcher emits a `.toInt()` coercion at the
 * dispatch site so the `when (__dispatchValue)` branches stay Int-
 * typed. The discriminator's inner kind is also `UShort`, so the wire
 * shape is 2 bytes big-endian (matching IEEE 802.3 — the existing
 * peek path under [com.ditchoom.buffer.codec.processor.CodecEmitter]'s
 * `peekableDispatcherInnerKinds] covers the 2-byte assembly). The
 * validator restricts `@PacketType.value` to 0..65535 for UShort
 * returns; values outside that range are a focused compile error.
 */
@JvmInline
@ProtocolMessage
value class EtherType(
    val raw: UShort,
) {
    @DispatchValue
    val type: UShort get() = raw
}

@DispatchOn(EtherType::class)
@ProtocolMessage
sealed interface EthernetFrameByEtherType {
    /** RFC 894 — IPv4 over Ethernet. */
    @PacketType(value = 0x0800)
    @ProtocolMessage
    data class Ipv4(
        val etherType: EtherType = EtherType(0x0800u),
    ) : EthernetFrameByEtherType

    /** RFC 826 — Address Resolution Protocol. */
    @PacketType(value = 0x0806)
    @ProtocolMessage
    data class Arp(
        val etherType: EtherType = EtherType(0x0806u),
    ) : EthernetFrameByEtherType

    /** IEEE 802.1Q — VLAN tag prefix. */
    @PacketType(value = 0x8100)
    @ProtocolMessage
    data class VlanTag(
        val etherType: EtherType = EtherType(0x8100u),
    ) : EthernetFrameByEtherType

    /** RFC 8200 — IPv6 over Ethernet. */
    @PacketType(value = 0x86DD)
    @ProtocolMessage
    data class Ipv6(
        val etherType: EtherType = EtherType(0x86DDu),
    ) : EthernetFrameByEtherType
}
