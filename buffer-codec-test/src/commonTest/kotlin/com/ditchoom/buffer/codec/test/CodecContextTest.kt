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
    // ========== CodecContext basics ==========

    @Test
    fun emptyContextReturnsNull() {
        val key = CodecContext.Key<String>("test")
        assertNull(DecodeContext.Empty[key])
        assertNull(EncodeContext.Empty[key])
    }

    @Test
    fun contextStoresAndRetrievesValue() {
        val key = CodecContext.Key<Int>("count")
        val ctx = DecodeContext.Empty.with(key, 42)
        assertEquals(42, ctx[key])
    }

    @Test
    fun contextIsImmutable() {
        val key = CodecContext.Key<Int>("count")
        val ctx1 = DecodeContext.Empty.with(key, 1)
        val ctx2 = ctx1.with(key, 2)
        assertEquals(1, ctx1[key])
        assertEquals(2, ctx2[key])
    }

    @Test
    fun sharedKeyWorksInBothContexts() {
        val versionKey = CodecContext.Key<Int>("protocol.version")
        val dCtx = DecodeContext.Empty.with(versionKey, 2)
        val eCtx = EncodeContext.Empty.with(versionKey, 2)
        assertEquals(2, dCtx[versionKey])
        assertEquals(2, eCtx[versionKey])
    }

    @Test
    fun keysComparedByIdentity() {
        val key1 = CodecContext.Key<String>("same.name")
        val key2 = CodecContext.Key<String>("same.name")
        val ctx = DecodeContext.Empty.with(key1, "hello")
        assertEquals("hello", ctx[key1])
        assertNull(ctx[key2]) // different key instance, even though same name
    }

    @Test
    fun multipleKeysInSameContext() {
        val nameKey = CodecContext.Key<String>("name")
        val countKey = CodecContext.Key<Int>("count")
        val ctx =
            DecodeContext.Empty
                .with(nameKey, "test")
                .with(countKey, 5)
        assertEquals("test", ctx[nameKey])
        assertEquals(5, ctx[countKey])
    }

    // ========== Context flows through @UseCodec ==========

    @Test
    fun contextFlowsThroughUseCodec() {
        // RgbCodec is a simple Codec<Rgb> — it doesn't read context by default.
        // But ColoredPointCodec forwards context to RgbCodec.decode(buffer, context).
        // We can verify this by using a custom codec that reads a key.

        // For this test, we verify that calling decode with context doesn't crash
        // and produces the same result as without context.
        val original = ColoredPoint(x = 10, y = 20, color = Rgb(255u, 128u, 0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColoredPointCodec.encode(buffer, original)
        buffer.resetForRead()

        val ctx = DecodeContext.Empty.with(CodecContext.Key("test"), "value")
        val decoded = ColoredPointCodec.decode(buffer, ctx)
        assertEquals(original, decoded)
    }

    @Test
    fun contextFlowsThroughSealedDispatch() {
        val original: MqttPacket = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec.encode(buffer, original)
        buffer.resetForRead()

        val ctx = DecodeContext.Empty.with(CodecContext.Key("test"), "value")
        val decoded = MqttPacketCodec.decode(buffer, ctx)
        assertTrue(decoded is MqttPacketConnAck)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeContextFlowsThroughUseCodec() {
        val original = ColoredPoint(x = 10, y = 20, color = Rgb(255u, 128u, 0u))
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)

        val ctx = EncodeContext.Empty.with(CodecContext.Key("test"), "value")
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
        // Context-free decode — should work via delegation to context overload with Empty
        val decoded = ColoredPointCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun contextIgnoredByFlatCodec() {
        // SimpleHeaderCodec has no @UseCodec or nested codecs — interface default handles context
        val key = CodecContext.Key<String>("ignored")
        val ctx = DecodeContext.Empty.with(key, "should be ignored")

        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        buffer.writeUByte(1u)
        buffer.writeUShort(100u)
        buffer.writeUInt(0xFFu)
        buffer.resetForRead()

        // decode(buffer, context) calls decode(buffer) via Codec interface default
        val decoded =
            com.ditchoom.buffer.codec.test.protocols.SimpleHeaderCodec
                .decode(buffer, ctx)
        assertEquals(1u.toUByte(), decoded.type)
    }
}
