package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Wire: [id:1][data:remaining-1][checksum:1]
 * The processor auto-reserves 1 byte from the remaining buffer for the trailing `checksum`.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class DataPacketByteTrailer(
    val id: UByte,
    @RemainingBytes val data: String,
    val checksum: UByte,
)

/**
 * Wire: [version:1][data:remaining-4][crc:4]
 * Larger fixed-size trailer to prove the reservation handles multi-byte primitives.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class DataPacketCrcTrailer(
    val version: UByte,
    @RemainingBytes val data: String,
    val crc: UInt,
)

/**
 * Wire: [id:1][data:remaining-3][flags:1][seq:2]
 * Multiple trailing primitives — processor sums them into the reservation.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class DataPacketMultiTrailer(
    val id: UByte,
    @RemainingBytes val data: String,
    val flags: UByte,
    val seq: UShort,
)
