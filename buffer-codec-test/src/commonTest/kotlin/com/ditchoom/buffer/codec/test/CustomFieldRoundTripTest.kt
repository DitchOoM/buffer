package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.codec.test.protocols.MixedPacket
import com.ditchoom.buffer.codec.test.protocols.MixedPacketCodec
import com.ditchoom.buffer.codec.test.protocols.PropertyBagPacket
import com.ditchoom.buffer.codec.test.protocols.PropertyBagPacketCodec
import com.ditchoom.buffer.codec.test.protocols.RepeatedPacket
import com.ditchoom.buffer.codec.test.protocols.RepeatedPacketCodec
import com.ditchoom.buffer.codec.test.protocols.VbiPacket
import com.ditchoom.buffer.codec.test.protocols.VbiPacketCodec
import com.ditchoom.buffer.variableByteSizeInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomFieldRoundTripTest {
    @Test
    fun vbiRoundTripSingleByte() {
        // length=0 requires 1 VBI byte
        roundTripVbi(VbiPacket(0x01u, 0, 0x7F.toByte()))
        // length=127 requires 1 VBI byte
        roundTripVbi(VbiPacket(0xFFu, 127, 0x00.toByte()))
    }

    @Test
    fun vbiRoundTripMultiByte() {
        // length=128 requires 2 VBI bytes
        roundTripVbi(VbiPacket(0x10u, 128, 0x42.toByte()))
        // length=268435455 (max) requires 4 VBI bytes
        roundTripVbi(VbiPacket(0xABu, 268435455, 0x01.toByte()))
    }

    @Test
    fun repeatedRoundTripEmpty() {
        val original = RepeatedPacket(0u, emptyList())
        val buffer = PlatformBuffer.allocate(64)
        RepeatedPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = RepeatedPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun repeatedRoundTripMultiple() {
        val original = RepeatedPacket(3u, listOf(1, 2, 3))
        val buffer = PlatformBuffer.allocate(64)
        RepeatedPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = RepeatedPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun propertyBagRoundTripEmpty() {
        val original = PropertyBagPacket(1u, emptyMap())
        val buffer = PlatformBuffer.allocate(64)
        PropertyBagPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = PropertyBagPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun propertyBagRoundTripMultipleEntries() {
        val original = PropertyBagPacket(2u, mapOf(1 to 42, 2 to 300, 3 to 0))
        val buffer = PlatformBuffer.allocate(128)
        PropertyBagPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = PropertyBagPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun mixedPacketRoundTrip() {
        val original =
            MixedPacket(
                id = 0x1234u,
                remaining = 42,
                name = "hello",
                props = mapOf(1 to 10, 2 to 20),
            )
        val buffer = PlatformBuffer.allocate(256)
        MixedPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = MixedPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun vbiSizeOfCorrectness() {
        val testCases =
            listOf(
                0 to 1,
                127 to 1,
                128 to 2,
                16383 to 2,
                16384 to 3,
                2097151 to 3,
                2097152 to 4,
                268435455 to 4,
            )
        for ((value, expectedBytes) in testCases) {
            assertEquals(expectedBytes, variableByteSizeInt(value), "variableByteSizeInt($value)")
            // Verify sizeOf matches actual encoded byte count
            val packet = VbiPacket(0u, value, 0)
            val buffer = PlatformBuffer.allocate(16)
            VbiPacketCodec.encode(buffer, packet)
            val actualTotalBytes = buffer.position()
            // total = 1 (header UByte) + VBI bytes + 1 (trailer Byte)
            val actualVbiBytes = actualTotalBytes - 2
            assertEquals(expectedBytes, actualVbiBytes, "Actual VBI bytes for value=$value")
        }
    }

    private fun roundTripVbi(original: VbiPacket) {
        val buffer = PlatformBuffer.allocate(16)
        VbiPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = VbiPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }
}
