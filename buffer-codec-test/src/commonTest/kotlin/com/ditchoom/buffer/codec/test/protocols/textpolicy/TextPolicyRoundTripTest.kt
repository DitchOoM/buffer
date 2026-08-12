package com.ditchoom.buffer.codec.test.protocols.textpolicy

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.MalformedTextException
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.TextPolicyKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextPolicyRoundTripTest {
    // Runtime-constructed: Kotlin/JS clean builds mangle unpaired-surrogate literals to '?'.
    private val loneHigh = Char(0xD800).toString()

    @Test
    fun wellFormedRoundTripsThroughAllTiers() {
        val original = TextPolicyMessage(topic = "a/b", note = "café ☕", payload = "data")
        val buffer = BufferFactory.Default.allocate(128)
        TextPolicyMessageCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        assertEquals(original, TextPolicyMessageCodec.decode(buffer, DecodeContext.Empty))
    }

    @Test
    fun fieldPinnedStrictThrowsTypedOnIllFormedInput() {
        val bad = TextPolicyMessage(topic = "a$loneHigh", note = "n", payload = "p")
        val buffer = BufferFactory.Default.allocate(128)
        assertFailsWith<MalformedTextException.UnpairedSurrogate> {
            TextPolicyMessageCodec.encode(buffer, bad, EncodeContext.Empty)
        }
    }

    @Test
    fun fieldPinnedProviderLenientSubstitutes() {
        val original = TextPolicyMessage(topic = "t", note = "n$loneHigh", payload = "p")
        val buffer = BufferFactory.Default.allocate(128)
        TextPolicyMessageCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        val decoded = TextPolicyMessageCodec.decode(buffer, DecodeContext.Empty)
        assertEquals("n�", decoded.note, "provider-pinned Lenient substitutes U+FFFD")
    }

    @Test
    fun unpinnedFieldDefaultsToStrictAndContextOverrides() {
        val bad = TextPolicyMessage(topic = "t", note = "n", payload = "p$loneHigh")
        val buffer = BufferFactory.Default.allocate(128)
        // Default tier: Utf8.Strict — ill-formed payload fails the encode.
        assertFailsWith<MalformedTextException.UnpairedSurrogate> {
            TextPolicyMessageCodec.encode(buffer, bad, EncodeContext.Empty)
        }
        // Context tier: inject Lenient — the same message now encodes, substituting.
        // (Fresh buffer: the strict encode above wrote topic/note before rejecting payload.)
        val lenientCtx = EncodeContext.Empty.with(TextPolicyKey, Utf8.Lenient)
        val retryBuffer = BufferFactory.Default.allocate(128)
        TextPolicyMessageCodec.encode(retryBuffer, bad, lenientCtx)
        retryBuffer.resetForRead()
        val decoded = TextPolicyMessageCodec.decode(retryBuffer, DecodeContext.Empty)
        assertEquals("p�", decoded.payload)
    }
}
