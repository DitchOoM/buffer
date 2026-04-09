package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.DispatchedFrame
import com.ditchoom.buffer.codec.test.protocols.DispatchedFrameCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchedFrameControlCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchedFrameDataCodec
import com.ditchoom.buffer.codec.test.protocols.FrameHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Round-trip tests for @Payload + @DispatchOn discriminator field.
 *
 * Validates the fix for the code-gen bug where the lambda-based `decode`
 * referenced an undeclared `context` variable when a variant has both
 * a @Payload field and a discriminator field (field whose type matches
 * the @DispatchOn type).
 */
class PayloadDiscriminatorRoundTripTest {
    @Test
    fun dataVariantLambdaDecodeRoundTrip() {
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x01) // byte1 = type 1 (Data)
        buf.writeByte(0x00) // byte2 = 0
        buf.writeString("Hello")
        buf.resetForRead()

        val frame = DispatchedFrameCodec.decode<String>(buf) { pr ->
            pr.readString(pr.remaining())
        }

        assertIs<DispatchedFrame.Data<*>>(frame)
        assertEquals(0x01.toUByte(), frame.header.byte1)
        assertEquals(0x00.toUByte(), frame.header.byte2)
        assertEquals("Hello", frame.payload)
    }

    @Test
    fun controlVariantDecodeRoundTrip() {
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x02) // byte1 = type 2 (Control)
        buf.writeByte(0xFF.toByte()) // byte2
        buf.writeString("ping")
        buf.resetForRead()

        val frame = DispatchedFrameCodec.decode<String>(buf) { pr ->
            pr.readString(pr.remaining())
        }

        assertIs<DispatchedFrame.Control>(frame)
        assertEquals(0x02.toUByte(), frame.header.byte1)
        assertEquals(0xFF.toUByte(), frame.header.byte2)
        assertEquals("ping", frame.message)
    }

    @Test
    fun dataVariantContextDecodeRoundTrip() {
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x01)
        buf.writeByte(0x00)
        buf.writeString("World")
        buf.resetForRead()

        val ctx = DecodeContext.Empty
            .with(DispatchedFrameDataCodec.PayloadDecodeKey) { pr ->
                pr.readString(pr.remaining())
            }

        val frame = DispatchedFrameCodec.decode(buf, ctx)

        assertIs<DispatchedFrame.Data<*>>(frame)
        assertEquals("World", frame.payload)
    }

    @Test
    fun dataVariantEncodeRoundTrip() {
        val original = DispatchedFrame.Data(
            header = FrameHeader(0x01u, 0x00u),
            payload = "Hello",
        )

        // Encode
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        DispatchedFrameCodec.encode<String>(buf, original) { writer, value ->
            writer.writeString(value)
        }
        buf.resetForRead()

        // Decode
        val decoded = DispatchedFrameCodec.decode<String>(buf) { pr ->
            pr.readString(pr.remaining())
        }

        assertIs<DispatchedFrame.Data<*>>(decoded)
        assertEquals(original.header, decoded.header)
        assertEquals(original.payload, decoded.payload)
    }

    @Test
    fun controlVariantEncodeRoundTrip() {
        val original = DispatchedFrame.Control(
            header = FrameHeader(0x02u, 0xFFu),
            message = "pong",
        )

        // Encode
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        DispatchedFrameCodec.encode<String>(buf, original) { _, _ -> }
        buf.resetForRead()

        // Decode
        val decoded = DispatchedFrameCodec.decode<String>(buf) { pr ->
            pr.readString(pr.remaining())
        }

        assertIs<DispatchedFrame.Control>(decoded)
        assertEquals(original.header, decoded.header)
        assertEquals(original.message, decoded.message)
    }

    @Test
    fun contextBasedEncodeDecodeRoundTrip() {
        val original = DispatchedFrame.Data(
            header = FrameHeader(0x01u, 0x42u),
            payload = "context-trip",
        )

        val encodeCtx = EncodeContext.Empty
            .with(DispatchedFrameDataCodec.PayloadEncodeKey) { writer, value ->
                writer.writeString(value as String)
            }

        val decodeCtx = DecodeContext.Empty
            .with(DispatchedFrameDataCodec.PayloadDecodeKey) { pr ->
                pr.readString(pr.remaining())
            }

        // Encode via context
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        DispatchedFrameCodec.encode(buf, original, encodeCtx)
        buf.resetForRead()

        // Decode via context
        val decoded = DispatchedFrameCodec.decode(buf, decodeCtx)

        assertIs<DispatchedFrame.Data<*>>(decoded)
        assertEquals(original.header, decoded.header)
        assertEquals(original.payload, decoded.payload)
    }
}
