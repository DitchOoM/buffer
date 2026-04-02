package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireBytes
import com.ditchoom.buffer.codec.annotations.WireOrder

/**
 * Test message exercising @WireOrder with various field types and combinations.
 * Models a mixed-endian protocol header (e.g., USB descriptors, some game formats).
 */
@ProtocolMessage
data class WireOrderMessage(
    val beByte: UByte, // single byte — no swap needed
    @WireOrder(Endianness.Little) val leShort: UShort,
    val beInt: Int, // buffer default (BE)
    @WireOrder(Endianness.Little) val leInt: UInt,
    @WireOrder(Endianness.Little) val leLong: Long,
)

/**
 * Test message exercising @WireOrder combined with @WireBytes.
 * Models a protocol with 3-byte LE length field (e.g., BLE ATT protocol).
 */
@ProtocolMessage
data class WireOrderCustomWidthMessage(
    val tag: UByte,
    @WireOrder(Endianness.Little) @WireBytes(3) val leLength: UInt,
    @WireOrder(Endianness.Little) @WireBytes(2) val leFlags: Int,
)
