package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The declared-size verification guard: when a `@LengthPrefixed` prefix is
 * written from a codec's DECLARED `Exact` wireSize, the generated encode
 * verifies the body actually produced that many bytes. A codec whose
 * wireSize and encode disagree must throw `EncodeException` — the
 * alternative is a frame whose prefix and body disagree, i.e. silent wire
 * corruption that only surfaces on the decode side of another machine.
 */
class MisdeclaredWireSizeTest {
    @Test
    fun misdeclaringCodecFailsLoudlyInsteadOfCorruptingFraming() {
        val ex =
            assertFailsWith<EncodeException> {
                MisdeclaredHostCodec.encodeToPlatformBuffer(MisdeclaredHost(kind = 1, inner = MisdeclaredInner(7u)))
            }
        assertTrue(
            "disagree" in (ex.message ?: ""),
            "expected the wireSize/encode disagreement diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun honestCodecComposesExactAndRoundTrips() {
        val msg = HonestHost(kind = 1, inner = HonestInner(0xDEADBEEFu))
        // 1 (kind) + 2 (prefix) + 4 (body).
        assertEquals(WireSize.Exact(7), HonestHostCodec.wireSize(msg, EncodeContext.Empty))
        val buf = HonestHostCodec.encodeToPlatformBuffer(msg)
        assertEquals(msg, HonestHostCodec.decode(buf, DecodeContext.Empty))
    }
}
