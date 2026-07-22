package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.test.protocols.http2.Http2SettingsFrameCodec
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.PropertyBagFrameCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.BoundedFrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Widen-guard audit for the remaining narrowing decode sites — every
 * wire-supplied length that narrows `buffer.limit()` must be rejected when
 * it exceeds the enclosing bound, since `setLimit` widens unchecked on
 * every platform (same decode-side hole as the `@LengthFrom` message
 * shapes; see `TlsRecordWidenGuardTest`).
 *
 * Covered here:
 *  - `@LengthFrom("sibling") List<T>` — [Http2SettingsFrameCodec]
 *  - `BoundingLengthCodec.applyBound` on a `@UseCodec` scalar — [BoundedFrameCodec]
 *  - `BoundingLengthCodec.applyBound` on a `@LengthPrefixed @UseCodec` list — [PropertyBagFrameCodec]
 */
class OuterBoundWidenGuardTest {
    /** SETTINGS frame whose uint24 claims 60 entry bytes; the carve leaves 6. */
    @Test
    fun lengthFromListCannotWidenPastTheOuterBound() {
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        buf.writeUByte(0u) // uint24 length = 60, LIES
        buf.writeUByte(0u)
        buf.writeUByte(60u)
        buf.writeUByte(4u) // type = SETTINGS
        buf.writeUByte(0u) // flags
        buf.writeUInt(0u) // streamId
        buf.writeUShort(1u) // one honest 6-byte entry
        buf.writeUInt(4096u)
        carveAndPad(buf)
        val failure =
            assertFailsWith<DecodeException> {
                Http2SettingsFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("Http2SettingsFrame.entries", failure.fieldPath)
    }

    /** Bounding `@UseCodec` scalar whose `applyBound` would land past the carve. */
    @Test
    fun boundingUseCodecScalarCannotWidenPastTheOuterBound() {
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x0102) // tag
        buf.writeByte(50) // length = 50 little-endian, LIES: the carve leaves 2
        buf.writeByte(0)
        buf.writeByte(0)
        buf.writeByte(0)
        buf.writeByte(0x68) // payload "hi"
        buf.writeByte(0x69)
        carveAndPad(buf)
        val failure =
            assertFailsWith<DecodeException> {
                BoundedFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("BoundedFrame.length", failure.fieldPath)
    }

    /** VBI-prefixed property list whose remaining-length claims 50; the carve leaves 10. */
    @Test
    fun boundingPrefixedListCannotWidenPastTheOuterBound() {
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        buf.writeByte(50) // MQTT var-byte-int = 50, LIES
        repeat(2) { i ->
            buf.writeUByte((i + 1).toUByte()) // property id
            buf.writeUInt(0xCAFEu) // property value
        }
        carveAndPad(buf)
        val failure =
            assertFailsWith<DecodeException> {
                PropertyBagFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("PropertyBagFrame.properties", failure.fieldPath)
    }

    /** Carve the outer bound at the honest frame end, with adjacent bytes beyond it. */
    private fun carveAndPad(buf: PlatformBuffer) {
        val frameEnd = buf.position()
        repeat(100) { buf.writeByte(0x5A) }
        buf.setLimit(buf.position())
        buf.position(0)
        buf.setLimit(frameEnd)
    }
}
