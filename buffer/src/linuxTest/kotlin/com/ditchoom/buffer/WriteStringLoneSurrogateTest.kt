package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the invalid-UTF-16 behavior of the native `writeString` fast path (PR #281).
 *
 * The bounds check is sized by simdutf `utf8_length_from_utf16le`, whose result for
 * invalid input (lone surrogates) is implementation-defined — but always an
 * over-estimate of what the validating converter writes, because
 * `convert_utf16le_to_utf8` stops at the first invalid code unit and returns 0.
 * The observable contract on Linux is therefore: a string containing a lone
 * surrogate is a bounds-safe no-op (position does not advance), and valid
 * surrogate pairs still transcode. These tests hold both `NativeBuffer` and
 * `NativeBufferSlice` overloads to that contract.
 *
 * Kept in linuxTest because the lone-surrogate outcome is platform-specific:
 * JVM charset encoders substitute a replacement character instead of rejecting.
 */
class WriteStringLoneSurrogateTest {
    private val loneHigh = "\uD800"
    private val loneLow = "\uDC00"
    private val emojiPair = "😀" // 😀 — 4 UTF-8 bytes

    @Test
    fun loneHighSurrogateIsBoundsSafeNoOp() {
        NativeBuffer.allocate(8).use { buffer ->
            buffer.writeString(loneHigh)
            assertEquals(0, buffer.position(), "lone high surrogate must not advance position")
        }
    }

    @Test
    fun loneLowSurrogateIsBoundsSafeNoOp() {
        NativeBuffer.allocate(8).use { buffer ->
            buffer.writeString(loneLow)
            assertEquals(0, buffer.position(), "lone low surrogate must not advance position")
        }
    }

    @Test
    fun loneSurrogateAfterValidPrefixDoesNotAdvancePosition() {
        NativeBuffer.allocate(16).use { buffer ->
            buffer.writeString("AB$loneHigh")
            assertEquals(0, buffer.position(), "invalid tail must reject the whole write")
        }
    }

    @Test
    fun loneSurrogateInLongStringDoesNotAdvancePosition() {
        // Long enough to leave the scalar tail and exercise the SIMD block path
        // of both the length estimate and the converter.
        val text = "a".repeat(64) + loneHigh + "b".repeat(64)
        NativeBuffer.allocate(256).use { buffer ->
            buffer.writeString(text)
            assertEquals(0, buffer.position(), "invalid middle must reject the whole write")
        }
    }

    @Test
    fun validSurrogatePairRoundTrips() {
        NativeBuffer.allocate(8).use { buffer ->
            buffer.writeString(emojiPair)
            assertEquals(4, buffer.position(), "surrogate pair must encode to 4 UTF-8 bytes")
            buffer.resetForRead()
            assertEquals(emojiPair, buffer.readString(4, Charset.UTF8).toString())
        }
    }

    @Test
    fun sliceLoneSurrogateIsBoundsSafeNoOp() {
        NativeBuffer.allocate(8).use { buffer ->
            val slice = buffer.slice()
            slice.writeString(loneHigh)
            assertEquals(0, slice.position(), "slice: lone surrogate must not advance position")
        }
    }

    @Test
    fun sliceValidSurrogatePairRoundTrips() {
        NativeBuffer.allocate(8).use { buffer ->
            val slice = buffer.slice()
            slice.writeString(emojiPair)
            assertEquals(4, slice.position(), "slice: surrogate pair must encode to 4 UTF-8 bytes")
            slice.resetForRead()
            assertEquals(emojiPair, slice.readString(4, Charset.UTF8))
        }
    }
}
