@file:Suppress("DEPRECATION") // exercises the bridges to the deprecated WireSize

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
    fun aNegativeSizeCannotBeExact() {
        assertEquals(-1L, assertFailsWith<NegativeWireByteCountException> { WireByteCount.exact(-1) }.bytes)
        assertFailsWith<NegativeWireByteCountException> { WireByteCount.from(WireSize.Exact(-3)) }
    }

    @Test
    fun theDeprecatedWireSizeBridgeMapsEveryState() {
        assertEquals(WireSize.Exact(7), WireByteCount.exact(7).toWireSize())
        assertEquals(WireSize.BackPatch, WireByteCount.BackPatch.toWireSize())
        // An Int cannot carry it, and BackPatch is always a correct answer.
        assertEquals(WireSize.BackPatch, WireByteCount.exact(Int.MAX_VALUE + 1L).toWireSize())
        assertEquals(WireByteCount.exact(7), WireByteCount.from(WireSize.Exact(7)))
        assertEquals(WireByteCount.BackPatch, WireByteCount.from(WireSize.BackPatch))
    }

    private object OldStyleEncoder : Encoder<Int> {
        override fun encode(
            buffer: WriteBuffer,
            value: Int,
            context: EncodeContext,
        ) {
            buffer.writeInt(value)
        }

        override fun wireSize(
            value: Int,
            context: EncodeContext,
        ): WireSize = WireSize.Exact(4)
    }

    private object NewStyleOnlyEncoder : Encoder<Int> {
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
    fun theDefaultWireBytesAsksTheDeprecatedWireSize() {
        assertEquals(WireByteCount.exact(4), OldStyleEncoder.wireBytes(1, EncodeContext.Empty))
        assertEquals(
            WireByteCount.BackPatch,
            object : Encoder<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) = Unit
            }.wireBytes(1, EncodeContext.Empty),
        )
    }

    /**
     * The sender's migration hazard, pinned: an encoder that overrides only `wireBytes`
     * answers BackPatch to a caller still on `wireSize`. That is still correct (BackPatch
     * always is), it only loses the exact pre-allocation.
     */
    @Test
    fun anEncoderOverridingOnlyWireBytesAnswersBackPatchToTheDeprecatedWireSize() {
        assertEquals(WireByteCount.exact(4), NewStyleOnlyEncoder.wireBytes(1, EncodeContext.Empty))
        assertEquals(WireSize.BackPatch, NewStyleOnlyEncoder.wireSize(1, EncodeContext.Empty))
    }

    @Test
    fun encodeToPlatformBufferAllocatesTheExactSize() {
        val buffer = NewStyleOnlyEncoder.encodeToPlatformBuffer(0x01020304)
        try {
            assertEquals(4, buffer.remaining())
            assertEquals(4, buffer.capacity)
            assertEquals(0x01020304, buffer.readInt())
        } finally {
            buffer.freeNativeMemory()
        }
    }
}
