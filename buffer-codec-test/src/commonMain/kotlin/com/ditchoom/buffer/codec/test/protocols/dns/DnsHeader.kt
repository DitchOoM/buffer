package com.ditchoom.buffer.codec.test.protocols.dns

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * DNS message header — RFC 1035 §4.1.1.
 *
 *   ```text
 *                                   1  1  1  1  1  1
 *     0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                       ID                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    QDCOUNT                    |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    ANCOUNT                    |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    NSCOUNT                    |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    ARCOUNT                    |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   ```
 *
 * Twelve bytes, all big-endian (network byte order). Stage B vector for
 * pure-BE multi-scalar `@ProtocolMessage` shapes — exercises message-
 * level `wireOrder = Endianness.Big` across six successive `UShort`
 * fields with no per-field overrides.
 *
 * The `flags` field is bit-packed (`QR`, `Opcode`, `AA`, `TC`, `RD`,
 * `RA`, `Z`, `RCODE`); modeling it as a single `UShort` keeps Stage B
 * focused on the multi-scalar shape and defers bit-packed logical-field
 * accessors to a value-class wrapper if the consumer wants them. The
 * `MySqlPacketHeader` value-class fixture in this module shows that
 * bit-packed shape; here, the raw `flags` UShort is sufficient for a
 * faithful wire round-trip.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class DnsHeader(
    val id: UShort,
    val flags: UShort,
    val qdCount: UShort,
    val anCount: UShort,
    val nsCount: UShort,
    val arCount: UShort,
)
