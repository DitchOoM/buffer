package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression for issue #156 — generated codec names must flatten across
 * the enclosing-type chain so that same-named nested data classes do not
 * collide on the KSP `createNewFile` write path.
 *
 * The fact that this test class compiles is the primary assertion: pre-fix,
 * KSP threw `FileAlreadyExistsException` and the whole test source set
 * failed to build. The round-trip and distinctness checks lock in the
 * dispatcher-side fix (the sealed parent dispatcher must reference variant
 * codecs by their flattened names, not their simple names).
 */
class Issue156NestedNameTest {
    @Test
    fun commandLedStateSetRoundTrips() {
        val original: Issue156Command = Issue156Command.LedStateSet(ledId = 0x05u, duty = 750u)
        val buf = BufferFactory.Default.allocate(64)
        Issue156CommandCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = Issue156CommandCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun responseLedStateSetRoundTrips() {
        val original: Issue156Response = Issue156Response.LedStateSet(ledId = 0x05u, duty = 750u)
        val buf = BufferFactory.Default.allocate(64)
        Issue156ResponseCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = Issue156ResponseCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun nestedVariantCodecsAreDistinctObjects() {
        // If flattening regressed, the test source set would fail to compile
        // (one of these identifiers would not exist). Asserting distinct
        // class names also guards against an accidental aliasing fix.
        val cmd = Issue156CommandLedStateSetCodec::class.simpleName
        val resp = Issue156ResponseLedStateSetCodec::class.simpleName
        assertNotEquals(cmd, resp)
    }
}
