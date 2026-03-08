package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.MixedPacket
import com.ditchoom.buffer.codec.test.protocols.MixedPacketCodec
import com.ditchoom.buffer.codec.test.protocols.PrefixedEntries
import com.ditchoom.buffer.codec.test.protocols.PrefixedEntriesCodec
import com.ditchoom.buffer.codec.test.protocols.PropertyBagPacket
import com.ditchoom.buffer.codec.test.protocols.PropertyBagPacketCodec
import com.ditchoom.buffer.codec.test.protocols.RepeatedPacket
import com.ditchoom.buffer.codec.test.protocols.RepeatedPacketCodec
import com.ditchoom.buffer.codec.test.protocols.ShortEntry
import com.ditchoom.buffer.codec.test.protocols.SubscribeByCount
import com.ditchoom.buffer.codec.test.protocols.SubscribeByCountCodec
import com.ditchoom.buffer.codec.test.protocols.SubscribeRemaining
import com.ditchoom.buffer.codec.test.protocols.SubscribeRemainingCodec
import com.ditchoom.buffer.codec.test.protocols.Subscription
import com.ditchoom.buffer.codec.test.protocols.VbiPacket
import com.ditchoom.buffer.codec.test.protocols.VbiPacketCodec
import com.ditchoom.buffer.variableByteSizeInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomFieldRoundTripTest {
    @Test
    fun vbiRoundTripSingleByte() {
        roundTripVbi(VbiPacket(0x01u, 0, 0x7F.toByte()))
        roundTripVbi(VbiPacket(0xFFu, 127, 0x00.toByte()))
    }

    @Test
    fun vbiRoundTripMultiByte() {
        roundTripVbi(VbiPacket(0x10u, 128, 0x42.toByte()))
        roundTripVbi(VbiPacket(0xABu, 268435455, 0x01.toByte()))
    }

    @Test
    fun repeatedRoundTripEmpty() {
        val original = RepeatedPacket(0u, emptyList())
        val buffer = BufferFactory.Default.allocate(64)
        RepeatedPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = RepeatedPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun repeatedRoundTripMultiple() {
        val original = RepeatedPacket(3u, listOf(ShortEntry(1), ShortEntry(2), ShortEntry(3)))
        val buffer = BufferFactory.Default.allocate(64)
        RepeatedPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = RepeatedPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun propertyBagRoundTripEmpty() {
        val original = PropertyBagPacket(1u, emptyMap())
        val buffer = BufferFactory.Default.allocate(64)
        PropertyBagPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = PropertyBagPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun propertyBagRoundTripMultipleEntries() {
        val original = PropertyBagPacket(2u, mapOf(1 to 42, 2 to 300, 3 to 0))
        val buffer = BufferFactory.Default.allocate(128)
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
        val buffer = BufferFactory.Default.allocate(256)
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
            val packet = VbiPacket(0u, value, 0)
            val buffer = BufferFactory.Default.allocate(16)
            VbiPacketCodec.encode(buffer, packet)
            val actualTotalBytes = buffer.position()
            val actualVbiBytes = actualTotalBytes - 2
            assertEquals(expectedBytes, actualVbiBytes, "Actual VBI bytes for value=$value")
        }
    }

    // ──────────────────────── List<NestedMessage> tests ────────────────────────

    @Test
    fun subscribeByCountRoundTrip() {
        val original =
            SubscribeByCount(
                packetId = 0x1234u,
                count = 2u,
                subscriptions =
                    listOf(
                        Subscription("sensor/temp", 1u),
                        Subscription("sensor/humidity", 0u),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(256)
        SubscribeByCountCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SubscribeByCountCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun subscribeByCountRoundTripEmpty() {
        val original = SubscribeByCount(packetId = 1u, count = 0u, subscriptions = emptyList())
        val buffer = BufferFactory.Default.allocate(64)
        SubscribeByCountCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SubscribeByCountCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun subscribeRemainingRoundTrip() {
        val original =
            SubscribeRemaining(
                packetId = 0x5678u,
                subscriptions =
                    listOf(
                        Subscription("a/b", 2u),
                        Subscription("c/d/e", 1u),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(256)
        SubscribeRemainingCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SubscribeRemainingCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun subscribeRemainingRoundTripSingle() {
        val original =
            SubscribeRemaining(
                packetId = 0x0001u,
                subscriptions = listOf(Subscription("test", 0u)),
            )
        val buffer = BufferFactory.Default.allocate(64)
        SubscribeRemainingCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SubscribeRemainingCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun prefixedEntriesRoundTrip() {
        val original =
            PrefixedEntries(
                header = 0xAAu,
                items = listOf(ShortEntry(100), ShortEntry(200), ShortEntry(300)),
            )
        val buffer = BufferFactory.Default.allocate(64)
        PrefixedEntriesCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = PrefixedEntriesCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun prefixedEntriesRoundTripEmpty() {
        val original = PrefixedEntries(header = 0x00u, items = emptyList())
        val buffer = BufferFactory.Default.allocate(64)
        PrefixedEntriesCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = PrefixedEntriesCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    private fun roundTripVbi(original: VbiPacket) {
        val buffer = BufferFactory.Default.allocate(16)
        VbiPacketCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = VbiPacketCodec.decode(buffer)
        assertEquals(original, decoded)
    }
}
