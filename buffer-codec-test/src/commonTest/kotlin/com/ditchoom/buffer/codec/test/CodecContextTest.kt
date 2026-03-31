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
import com.ditchoom.buffer.codec.test.protocols.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAck
import com.ditchoom.buffer.codec.test.protocols.Rgb
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
