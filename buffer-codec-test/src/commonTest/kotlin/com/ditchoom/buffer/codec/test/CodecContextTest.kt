package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.CodecContext
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.ColoredPoint
import com.ditchoom.buffer.codec.test.protocols.ColoredPointCodec
import com.ditchoom.buffer.codec.test.protocols.ConnAckFlags
import com.ditchoom.buffer.codec.test.protocols.ConnectReturnCode
import com.ditchoom.buffer.codec.test.protocols.ContextColoredPoint
import com.ditchoom.buffer.codec.test.protocols.ContextColoredPointCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacket
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAck
import com.ditchoom.buffer.codec.test.protocols.Rgb
import com.ditchoom.buffer.codec.test.protocols.RgbOffsetKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecContextTest {
    // Test keys as data objects (preferred pattern)
    private data object StringKey : CodecContext.Key<String>()

    private data object IntKey : CodecContext.Key<Int>()

    private data object CountKey : CodecContext.Key<Int>()

    private data object NameKey : CodecContext.Key<String>()

    // Two keys with same type — proves identity comparison
    private data object Key1 : CodecContext.Key<String>()

    private data object Key2 : CodecContext.Key<String>()

    // Shared key usable in both DecodeContext and EncodeContext
    private data object VersionKey : CodecContext.Key<Int>()

    // ========== CodecContext basics ==========

    @Test
    fun emptyContextReturnsNull() {
        assertNull(DecodeContext.Empty[StringKey])
        assertNull(EncodeContext.Empty[StringKey])
    }

    @Test
    fun contextStoresAndRetrievesValue() {
        val ctx = DecodeContext.Empty.with(IntKey, 42)
        assertEquals(42, ctx[IntKey])
    }

    @Test
    fun contextIsImmutable() {
        val ctx1 = DecodeContext.Empty.with(CountKey, 1)
        val ctx2 = ctx1.with(CountKey, 2)
        assertEquals(1, ctx1[CountKey])
        assertEquals(2, ctx2[CountKey])
    }

    @Test
    fun sharedKeyWorksInBothContexts() {
        val dCtx = DecodeContext.Empty.with(VersionKey, 2)
        val eCtx = EncodeContext.Empty.with(VersionKey, 2)
        assertEquals(2, dCtx[VersionKey])
        assertEquals(2, eCtx[VersionKey])
    }

    @Test
    fun keysComparedByIdentity() {
        val ctx = DecodeContext.Empty.with(Key1, "hello")
        assertEquals("hello", ctx[Key1])
        assertNull(ctx[Key2]) // different key, same type
    }

    @Test
    fun multipleKeysInSameContext() {
        val ctx =
            DecodeContext.Empty
                .with(NameKey, "test")
                .with(CountKey, 5)
        assertEquals("test", ctx[NameKey])
        assertEquals(5, ctx[CountKey])
    }

    // ========== Context flows through @UseCodec ==========

    @Test
    fun contextFlowsThroughUseCodec() {
        val original = ColoredPoint(x = 10, y = 20, color = Rgb(255u, 128u, 0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColoredPointCodec.encode(buffer, original)
        buffer.resetForRead()

        val ctx = DecodeContext.Empty.with(StringKey, "value")
        val decoded = ColoredPointCodec.decode(buffer, ctx)
        assertEquals(original, decoded)
    }

    @Test
    fun contextFlowsThroughSealedDispatch() {
        val original: MqttPacket = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buffer, original)
        buffer.resetForRead()

        val ctx = DecodeContext.Empty.with(StringKey, "value")
        val decoded = MqttPacketCodec.decode(buffer, ctx)
        assertTrue(decoded is MqttPacketConnAck)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeContextFlowsThroughUseCodec() {
        val original = ColoredPoint(x = 10, y = 20, color = Rgb(255u, 128u, 0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)

        val ctx = EncodeContext.Empty.with(StringKey, "value")
        ColoredPointCodec.encode(buffer, original, ctx)
        buffer.resetForRead()

        val decoded = ColoredPointCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    // ========== Backward compat ==========

    @Test
    fun decodeWithoutContextStillWorks() {
        val original = ColoredPoint(x = 1, y = 2, color = Rgb(10u, 20u, 30u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColoredPointCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = ColoredPointCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun contextIgnoredByFlatCodec() {
        val ctx = DecodeContext.Empty.with(StringKey, "should be ignored")

        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        buffer.writeUByte(1u)
        buffer.writeUShort(100u)
        buffer.writeUInt(0xFFu)
        buffer.resetForRead()

        val decoded =
            com.ditchoom.buffer.codec.test.protocols.SimpleHeaderCodec
                .decode(buffer, ctx)
        assertEquals(1u.toUByte(), decoded.type)
    }

    // ========== No-context and empty-context produce identical results ==========

    @Test
    fun sealedDispatchDecodeIdenticalWithAndWithoutContext() {
        val original: MqttPacket = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))

        val buf1 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buf1, original)
        buf1.resetForRead()
        val result1 = MqttPacketCodec.decode(buf1)

        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buf2, original)
        buf2.resetForRead()
        val result2 = MqttPacketCodec.decode(buf2, DecodeContext.Empty)

        assertEquals(result1, result2)
    }

    @Test
    fun dispatchOnDecodeIdenticalWithAndWithoutContext() {
        val original: DispatchOnPacket = DispatchOnPacket.TypeConnect(4u, 60u)

        val buf1 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        DispatchOnPacketCodec.encode(buf1, original)
        buf1.resetForRead()
        val result1 = DispatchOnPacketCodec.decode(buf1)

        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        DispatchOnPacketCodec.encode(buf2, original)
        buf2.resetForRead()
        val result2 = DispatchOnPacketCodec.decode(buf2, DecodeContext.Empty)

        assertEquals(result1, result2)
    }

    @Test
    fun flatCodecDecodeIdenticalWithAndWithoutContext() {
        val original = ColoredPoint(x = 5, y = 10, color = Rgb(100u, 200u, 50u))

        val buf1 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColoredPointCodec.encode(buf1, original)
        buf1.resetForRead()
        val result1 = ColoredPointCodec.decode(buf1)

        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColoredPointCodec.encode(buf2, original)
        buf2.resetForRead()
        val result2 = ColoredPointCodec.decode(buf2, DecodeContext.Empty)

        assertEquals(result1, result2)
    }

    @Test
    fun sealedDispatchEncodeIdenticalWithAndWithoutContext() {
        val original: MqttPacket = MqttPacketConnAck(ConnAckFlags(1u), ConnectReturnCode(2u))

        val buf1 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buf1, original)

        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buf2, original, EncodeContext.Empty)

        assertEquals(buf1.position(), buf2.position())
        for (i in 0 until buf1.position()) {
            assertEquals(buf1[i], buf2[i], "Byte mismatch at index $i")
        }
    }

    @Test
    fun dispatchOnEncodeIdenticalWithAndWithoutContext() {
        val original: DispatchOnPacket = DispatchOnPacket.TypePubAck(999u)

        val buf1 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        DispatchOnPacketCodec.encode(buf1, original)

        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        DispatchOnPacketCodec.encode(buf2, original, EncodeContext.Empty)

        assertEquals(buf1.position(), buf2.position())
        for (i in 0 until buf1.position()) {
            assertEquals(buf1[i], buf2[i], "Byte mismatch at index $i")
        }
    }

    // ========== @UseCodec context consumption ==========

    @Test
    fun useCodecReceivesContextDuringDecode() {
        val original = ContextColoredPoint(1, 2, Rgb(10u, 20u, 30u))

        // Encode with no offset — writes raw values (10, 20, 30)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ContextColoredPointCodec.encode(buffer, original)
        buffer.resetForRead()

        // Decode with offset=5 — if context flows through, codec adds 5 to each channel
        val ctx = DecodeContext.Empty.with(RgbOffsetKey, 5)
        val decoded = ContextColoredPointCodec.decode(buffer, ctx)
        assertEquals(Rgb(15u, 25u, 35u), decoded.color)
    }

    @Test
    fun useCodecReceivesContextDuringEncode() {
        val original = ContextColoredPoint(1, 2, Rgb(50u, 60u, 70u))

        // Encode with offset=10 — codec subtracts 10 from each channel
        val eCtx = EncodeContext.Empty.with(RgbOffsetKey, 10)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ContextColoredPointCodec.encode(buffer, original, eCtx)
        buffer.resetForRead()

        // Decode with offset=10 — codec adds 10 back
        val dCtx = DecodeContext.Empty.with(RgbOffsetKey, 10)
        val decoded = ContextColoredPointCodec.decode(buffer, dCtx)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecWithoutContextUsesDefaultBehavior() {
        val original = ContextColoredPoint(1, 2, Rgb(10u, 20u, 30u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ContextColoredPointCodec.encode(buffer, original)
        buffer.resetForRead()

        // No context — offset defaults to 0, values unchanged
        val decoded = ContextColoredPointCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecContextProduceDifferentResultWithAndWithoutContext() {
        val original = ContextColoredPoint(1, 2, Rgb(10u, 20u, 30u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ContextColoredPointCodec.encode(buffer, original)

        // Decode without context
        buffer.resetForRead()
        val withoutCtx = ContextColoredPointCodec.decode(buffer)

        // Re-encode and decode with context
        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ContextColoredPointCodec.encode(buf2, original)
        buf2.resetForRead()
        val ctx = DecodeContext.Empty.with(RgbOffsetKey, 5)
        val withCtx = ContextColoredPointCodec.decode(buf2, ctx)

        // Same x/y, different color — proves context affected decode
        assertEquals(withoutCtx.x, withCtx.x)
        assertEquals(withoutCtx.y, withCtx.y)
        assertNotEquals(withoutCtx.color, withCtx.color)
    }
}
