package com.ditchoom.buffer.codec

import com.ditchoom.buffer.WriteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireByteCountTest {
    private fun describe(size: WireByteCount): String = size.fold(exact = { "exact:$it" }, backPatch = { "backPatch" })

    @Test
    fun eachStateFoldsToItsOwnBranch() {
        assertEquals("exact:0", describe(WireByteCount.exact(0)))
        assertEquals("exact:7", describe(WireByteCount.exact(7)))
        assertEquals("exact:${Long.MAX_VALUE}", describe(WireByteCount.exact(Long.MAX_VALUE)))
        assertEquals("backPatch", describe(WireByteCount.BackPatch))
        assertEquals("WireByteCount.exact(7)", WireByteCount.exact(7).toString())
    }

    @Test
    fun aNegativeExactSizeIsAnEncodeFailure() {
        assertFailsWith<EncodeException> { WireByteCount.exact(-1) }
    }

    private object ExactEncoder : Encoder<Int> {
        override fun encode(
            buffer: WriteBuffer,
            value: Int,
            context: EncodeContext,
        ) {
            buffer.writeInt(value)
        }

        override fun wireBytes(
            value: Int,
            context: EncodeContext,
        ): WireByteCount = WireByteCount.exact(4)
    }

    @Test
    fun anEncoderThatOverridesNothingBackPatches() {
        val encoder =
            object : Encoder<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) = Unit
            }
        assertEquals(WireByteCount.BackPatch, encoder.wireBytes(1, EncodeContext.Empty))
    }

    @Test
    fun encodeToPlatformBufferAllocatesTheExactSize() {
        val buffer = ExactEncoder.encodeToPlatformBuffer(0x01020304)
        try {
            assertEquals(4, buffer.remaining())
            assertEquals(4, buffer.capacity)
            assertEquals(0x01020304, buffer.readInt())
        } finally {
            buffer.freeNativeMemory()
        }
    }
}
