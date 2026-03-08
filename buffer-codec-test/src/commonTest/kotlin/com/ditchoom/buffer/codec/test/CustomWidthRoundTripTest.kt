package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.CustomWidthMessage
import com.ditchoom.buffer.codec.test.protocols.CustomWidthMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomWidthRoundTripTest {
    @Test
    fun `round trip custom width message`() {
        val original =
            CustomWidthMessage(
                flags = 0xAAu,
                signedValue = 0x123456,
                unsignedValue = 0x789ABCu,
                trailer = 0xFFu,
            )
        val buffer = BufferFactory.Default.allocate(64)
        CustomWidthMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = CustomWidthMessageCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip negative signed value`() {
        val original =
            CustomWidthMessage(
                flags = 0x01u,
                signedValue = -1,
                unsignedValue = 0xFFFFFFu,
                trailer = 0x00u,
            )
        val buffer = BufferFactory.Default.allocate(64)
        CustomWidthMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = CustomWidthMessageCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip all zeros`() {
        val original =
            CustomWidthMessage(
                flags = 0u,
                signedValue = 0,
                unsignedValue = 0u,
                trailer = 0u,
            )
        val buffer = BufferFactory.Default.allocate(64)
        CustomWidthMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = CustomWidthMessageCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip max 24bit values`() {
        val original =
            CustomWidthMessage(
                flags = 0xFFu,
                signedValue = 0x7FFFFF, // max positive 24-bit signed
                unsignedValue = 0xFFFFFFu, // max 24-bit unsigned
                trailer = 0xFFu,
            )
        val buffer = BufferFactory.Default.allocate(64)
        CustomWidthMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = CustomWidthMessageCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip min 24bit signed value`() {
        val original =
            CustomWidthMessage(
                flags = 0x80u,
                signedValue = -8388608, // min 24-bit signed (0x800000 sign-extended)
                unsignedValue = 1u,
                trailer = 0x42u,
            )
        val buffer = BufferFactory.Default.allocate(64)
        CustomWidthMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = CustomWidthMessageCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `wire size is 8 bytes`() {
        // 1 (UByte) + 3 (Int@WireBytes(3)) + 3 (UInt@WireBytes(3)) + 1 (UByte) = 8
        val msg =
            CustomWidthMessage(
                flags = 0u,
                signedValue = 0,
                unsignedValue = 0u,
                trailer = 0u,
            )
        assertEquals(8, CustomWidthMessageCodec.sizeOf(msg))
    }
}
