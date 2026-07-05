package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [encodeToPlatformBuffer] over both the Exact-size (Ping) and BackPatch (Echo) codec
 * paths, through both the native and managed factories.
 */
class CodecEncodingTest {
    @Test
    fun encodeDecode_exactSizeVariant() {
        for (factory in listOf(BufferFactory.Default, BufferFactory.managed())) {
            val value = Command.Ping(ts = 0x1122_3344_5566_7788L)
            val buffer = CommandCodec.encodeToPlatformBuffer(value, factory)
            assertEquals(value, CommandCodec.decode(buffer, DecodeContext.Empty))
        }
    }

    @Test
    fun encodeDecode_backPatchVariant() {
        for (factory in listOf(BufferFactory.Default, BufferFactory.managed())) {
            val value = Command.Echo(msg = "héllo 🌍 from the codec bridge")
            val buffer = CommandCodec.encodeToPlatformBuffer(value, factory)
            assertEquals(value, CommandCodec.decode(buffer, DecodeContext.Empty))
        }
    }

    @Test
    fun encode_growsPastInitialEstimateForLargePayload() {
        // Force the BackPatch grow-on-overflow loop with a payload far larger than MIN_ESTIMATE.
        val value = Command.Echo(msg = "x".repeat(5000))
        val buffer = CommandCodec.encodeToPlatformBuffer(value, BufferFactory.Default)
        assertEquals(value, CommandCodec.decode(buffer, DecodeContext.Empty))
    }
}
