package com.ditchoom.buffer.codec.test.protocols.visibility

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #175 — codecs inherit the source class's visibility. The
 * strongest assertion is structural: this test references
 * `InternalPacketCodec` / `InternalCommandCodec` (generated from
 * `internal` classes) and the module compiles, which it could not if the
 * codecs were emitted `public` over `internal` return types. The
 * round-trips below confirm the `internal` codecs still function.
 */
class InternalVisibilityCodecTest {
    @Test
    fun internalDataClassCodecRoundTrips() {
        val original = InternalPacket(id = 0x12345678, name = "héllo")
        val buf = BufferFactory.Default.allocate(64)
        InternalPacketCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(original, InternalPacketCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun internalSealedDispatcherRoundTrips() {
        roundTripCommand(InternalCommand.Ping(ts = 0x1122_3344_5566_7788L))
        roundTripCommand(InternalCommand.Echo(msg = "hi"))
    }

    private fun roundTripCommand(original: InternalCommand) {
        val buf = BufferFactory.Default.allocate(64)
        InternalCommandCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(original, InternalCommandCodec.decode(buf, DecodeContext.Empty))
    }
}
