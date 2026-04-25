package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.ProbeProp
import com.ditchoom.buffer.codec.test.protocols.ProbePropCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Empirical investigation of the gaps that force `MqttPropertyCodecExt.kt`'s 265 lines
 * of hand-written codec code. Each test isolates one suspected gap.
 */
class V5GapProbeTest {
    /** Gap probe 1: dual `@Payload` variants with DISTINCT type parameters round-trip cleanly. */
    @Test
    fun dualPayloadDistinctTypesRoundTrip() {
        val original: ProbeProp =
            ProbeProp.CorrelationProbe(
                length = 5u,
                data = "hello", // CD = String
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbePropCodec.encode<Int, String>(
            buffer,
            original,
            encodeAuthDataProbeData = { buf, v -> buf.writeInt(v) },
            encodeCorrelationProbeData = { buf, v -> buf.writeString(v) },
        )
        buffer.resetForRead()
        val decoded =
            ProbePropCodec.decode<Int, String>(
                buffer,
                decodeAuthDataProbeData = { reader -> reader.readInt() },
                decodeCorrelationProbeData = { reader -> reader.readString(reader.remaining()) },
            )
        assertTrue(decoded is ProbeProp.CorrelationProbe<*>)
        assertEquals(5u.toUShort(), decoded.length)
        assertEquals("hello", decoded.data)
    }

    /** Gap probe 1b: AuthDataProbe with the OTHER (Int) payload type round-trips. */
    @Test
    fun authPayloadRoundTrip() {
        val original: ProbeProp =
            ProbeProp.AuthDataProbe(
                length = 4u,
                data = 0x12345678, // AD = Int
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbePropCodec.encode<Int, String>(
            buffer,
            original,
            encodeAuthDataProbeData = { buf, v -> buf.writeInt(v) },
            encodeCorrelationProbeData = { buf, v -> buf.writeString(v) },
        )
        buffer.resetForRead()
        val decoded =
            ProbePropCodec.decode<Int, String>(
                buffer,
                decodeAuthDataProbeData = { reader -> reader.readInt() },
                decodeCorrelationProbeData = { reader -> reader.readString(reader.remaining()) },
            )
        assertTrue(decoded is ProbeProp.AuthDataProbe<*>)
        assertEquals(0x12345678, decoded.data)
    }

    /** Gap probe 1c: a non-payload property (UShortProp) round-trips through the same dispatcher. */
    @Test
    fun nonPayloadPropertyRoundTrip() {
        val original: ProbeProp = ProbeProp.UShortProp(0x1234u)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbePropCodec.encode<Int, String>(
            buffer,
            original,
            encodeAuthDataProbeData = { buf, v -> buf.writeInt(v) },
            encodeCorrelationProbeData = { buf, v -> buf.writeString(v) },
        )
        buffer.resetForRead()
        val decoded =
            ProbePropCodec.decode<Int, String>(
                buffer,
                decodeAuthDataProbeData = { reader -> reader.readInt() },
                decodeCorrelationProbeData = { reader -> reader.readString(reader.remaining()) },
            )
        assertTrue(decoded is ProbeProp.UShortProp)
        assertEquals(0x1234u.toUShort(), decoded.value)
    }

    /**
     * Gap probe — byte-prefixed property-bag wrapper round-trips a list of mixed properties.
     * Verifies the existing `@LengthPrefixed` (fixed-width) + `@RemainingBytes List<T>` path
     * does the byte-slicing correctly. The only thing missing for true MQTT v5 is replacing
     * the 2-byte Short prefix with a VBI prefix.
     */
    @Test
    fun byteSlicedPropertyBagRoundTrip() {
        val original =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixed(
                packetId = 7u,
                bag =
                    com.ditchoom.buffer.codec.test.protocols.ProbePropertyBag(
                        items =
                            listOf(
                                ProbeProp.UShortProp(0x1234u),
                                ProbeProp.StringPair("k", "v"),
                                ProbeProp.BoolProp(0x01u),
                            ),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec.decode(buffer)
        assertEquals(7u.toUShort(), decoded.packetId)
        assertEquals(3, decoded.bag.items.size)
        assertEquals(0x1234u.toUShort(), (decoded.bag.items[0] as ProbeProp.UShortProp).value)
        val pair = decoded.bag.items[1] as ProbeProp.StringPair
        assertEquals("k", pair.key)
        assertEquals("v", pair.value)
        assertEquals(0x01u.toUByte(), (decoded.bag.items[2] as ProbeProp.BoolProp).raw)
    }

    /** Empty property bag still round-trips (writes 2-byte zero prefix + nothing). */
    @Test
    fun emptyByteSlicedPropertyBagRoundTrip() {
        val original =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixed(
                packetId = 1u,
                bag = com.ditchoom.buffer.codec.test.protocols.ProbePropertyBag(items = emptyList()),
            )
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec.decode(buffer)
        assertEquals(1u.toUShort(), decoded.packetId)
        assertEquals(0, decoded.bag.items.size)
    }

    /**
     * Probe 4: cascading `@WhenRemaining` over (reason code, length-prefixed bag) produces
     * the v5 ack shape end-to-end. With a VBI-prefix variant in the LengthPrefix enum this
     * IS the generated shape MQTT v5 needs.
     */
    @Test
    fun whenRemainingCascadeWithLengthPrefixedBagRoundTrip() {
        // Minimal — only packetId
        val minimal = com.ditchoom.buffer.codec.test.protocols.ProbeAckOptional(packetId = 1u)
        var buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.encode(buf, minimal)
        assertEquals(2, buf.position())
        buf.resetForRead()
        val decodedMin = com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.decode(buf)
        assertEquals(minimal, decodedMin)

        // Reason code only
        val reasonOnly =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckOptional(packetId = 2u, reasonCode = 0x10u)
        buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.encode(buf, reasonOnly)
        assertEquals(3, buf.position())
        buf.resetForRead()
        assertEquals(reasonOnly, com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.decode(buf))

        // Reason code + bag
        val full =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckOptional(
                packetId = 3u,
                reasonCode = 0x80u,
                bag =
                    com.ditchoom.buffer.codec.test.protocols.ProbePropertyBag(
                        items = listOf(ProbeProp.UShortProp(0x0FFu)),
                    ),
            )
        buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.encode(buf, full)
        buf.resetForRead()
        val decodedFull = com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec.decode(buf)
        assertEquals(3u.toUShort(), decodedFull.packetId)
        assertEquals(0x80u.toUByte(), decodedFull.reasonCode)
        assertEquals(1, decodedFull.bag!!.items.size)
    }

    /** Gap probe 2: does the generator produce boolean-property validation (must be 0 or 1)? */
    @Test
    fun booleanPropertyAcceptsOutOfSpecValue() {
        // BoolProp is a value class wrapping UByte; the generator does NOT validate that
        // raw is 0 or 1 per MQTT 5.0 spec. Round-trip a value of 2 — should succeed,
        // proving the validation gap.
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        // Hand-write the wire bytes: id=0x01, value=0x02 (out of spec)
        buffer.writeByte(0x01.toByte())
        buffer.writeByte(0x02.toByte())
        buffer.resetForRead()
        val decoded =
            ProbePropCodec.decode<Int, String>(
                buffer,
                decodeAuthDataProbeData = { _ -> 0 },
                decodeCorrelationProbeData = { _ -> "" },
            )
        assertTrue(decoded is ProbeProp.BoolProp)
        // No validation error — generator does NOT enforce 0/1.
        assertEquals(0x02u.toUByte(), decoded.raw)
    }
}
