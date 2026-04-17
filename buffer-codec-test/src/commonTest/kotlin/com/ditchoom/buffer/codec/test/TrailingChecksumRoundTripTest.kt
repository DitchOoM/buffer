package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.test.protocols.DataPacketByteTrailer
import com.ditchoom.buffer.codec.test.protocols.DataPacketByteTrailerCodec
import com.ditchoom.buffer.codec.test.protocols.DataPacketCrcTrailer
import com.ditchoom.buffer.codec.test.protocols.DataPacketCrcTrailerCodec
import com.ditchoom.buffer.codec.test.protocols.DataPacketMultiTrailer
import com.ditchoom.buffer.codec.test.protocols.DataPacketMultiTrailerCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for issue #151 Change 2b: fields after `@RemainingBytes` with fixed wire size
 * are auto-reserved by the processor. No annotation parameters — inference is structural.
 */
class TrailingChecksumRoundTripTest {
    @Test
    fun byteTrailerRoundTrips() {
        val original = DataPacketByteTrailer(id = 0x07u, data = "hello world", checksum = 0xABu)
        val decoded = DataPacketByteTrailerCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun byteTrailerEmptyDataRoundTrips() {
        val original = DataPacketByteTrailer(id = 0x01u, data = "", checksum = 0xFFu)
        val decoded = DataPacketByteTrailerCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun crcTrailerRoundTrips() {
        val original =
            DataPacketCrcTrailer(
                version = 0x02u,
                data = "payload-bytes-here",
                crc = 0xDEADBEEFu,
            )
        val decoded = DataPacketCrcTrailerCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun multiTrailerRoundTrips() {
        val original =
            DataPacketMultiTrailer(
                id = 0x42u,
                data = "arbitrary variable data",
                flags = 0x10u,
                seq = 0x1234u,
            )
        val decoded = DataPacketMultiTrailerCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }
}
