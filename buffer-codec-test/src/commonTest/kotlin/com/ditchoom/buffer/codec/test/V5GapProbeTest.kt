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

    /**
     * Probe 5: `@WhenTrue` composes with an SPI-driven custom strategy (`@PropertyBag`).
     * Round-trips both branches:
     *  - willFlag = false → willProperties is absent on the wire and decodes back as null.
     *  - willFlag = true  → willProperties is encoded and decoded via the SPI extension fns.
     *
     * If both branches round-trip, the v5 CONNECT migration can declare will properties as
     * `@WhenTrue("flags.willFlag") @MqttProperties Collection<MqttProperty>? = null` directly
     * — no processor change required.
     */
    @Test
    fun whenTrueComposesWithMqttPropertiesProvider() {
        val codec = com.ditchoom.buffer.codec.test.protocols.ProbeConnectWithWillPropsCodec

        // willFlag = false — willProperties absent on wire
        val noWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeConnectWithWillProps(
                flags = com.ditchoom.buffer.codec.test.protocols.ProbeConnectFlags(0x00u),
                properties = mapOf(1 to 42),
                willProperties = null,
            )
        var buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, noWill)
        val sizeNoWill = buf.position()
        buf.resetForRead()
        val decodedNoWill = codec.decode(buf)
        assertEquals(noWill, decodedNoWill)

        // willFlag = true — willProperties present on wire
        val withWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeConnectWithWillProps(
                flags = com.ditchoom.buffer.codec.test.protocols.ProbeConnectFlags(0x04u),
                properties = mapOf(1 to 42),
                willProperties = mapOf(2 to 7, 3 to 99),
            )
        buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, withWill)
        val sizeWithWill = buf.position()
        buf.resetForRead()
        val decodedWithWill = codec.decode(buf)
        assertEquals(withWill, decodedWithWill)

        // The will-properties bag should contribute strictly more bytes when present.
        assertTrue(sizeWithWill > sizeNoWill)
    }

    /**
     * Probe 6: stacking `@LengthPrefixed` on top of an SPI custom strategy (`@PropertyBag`)
     * is silently ignored by the processor — the SPI branch in `FieldAnalyzer.resolveStrategy`
     * returns before any length-prefix inspection happens. The wire bytes from a plain
     * `@PropertyBag` field and a `@LengthPrefixed @PropertyBag` field are identical.
     *
     * Implication for the v5 migration: the SPI is self-bounded (the property bag carries
     * its own VBI length prefix internally), and combining it with external length annotations
     * is meaningless. The recommendation is to never combine them; this probe documents that
     * the processor currently allows but does not honor the combination — a future processor
     * change should make the combination a hard error to remove the footgun.
     */
    @Test
    fun mqttPropertiesWithExternalLengthPrefixIsIgnored() {
        val plain =
            com.ditchoom.buffer.codec.test.protocols.ProbePropBagPlain(
                packetId = 1u,
                properties = mapOf(1 to 42, 2 to 7),
            )
        val withPrefix =
            com.ditchoom.buffer.codec.test.protocols.ProbePropBagWithRedundantPrefix(
                packetId = 1u,
                properties = mapOf(1 to 42, 2 to 7),
            )

        val plainBuf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbePropBagPlainCodec.encode(plainBuf, plain)
        val plainSize = plainBuf.position()

        val prefBuf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbePropBagWithRedundantPrefixCodec
            .encode(prefBuf, withPrefix)
        val prefSize = prefBuf.position()

        // Identical wire size — `@LengthPrefixed` was silently dropped by the SPI branch.
        assertEquals(plainSize, prefSize)

        plainBuf.resetForRead()
        prefBuf.resetForRead()
        repeat(plainSize) {
            assertEquals(plainBuf.readByte(), prefBuf.readByte())
        }
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

    /**
     * Probe 7 — regression test for B-6: the codec processor must emit `@WhenTrue` if-blocks
     * for non-payload conditional fields in a sealed `@PacketType` variant that ALSO carries
     * a `<@Payload P>` type parameter.
     *
     * Background: while migrating MQTT v5 CONNECT to a sealed-tree variant, an apparent bug
     * surfaced where the generated codec read/wrote will-* fields unconditionally instead
     * of guarding them with `if (flags.willFlag) { ... }`. Investigation showed the bug was
     * a stale-cache artifact — fresh KSP run produces correct code. This probe locks the
     * correct shape in so any future processor regression that loses condition emission for
     * sealed-variant + @Payload + @WhenTrue is caught.
     *
     * Round-trip exercises both branches:
     *   willFlag=false → optional fields absent on wire, decoded as null
     *   willFlag=true  → optional fields present on wire, decoded back identically
     */
    @Test
    fun whenTrueComposesInsideSealedPayloadVariant() {
        // Round-trip via the @DispatchOn dispatcher so the discriminator byte is consumed
        // properly: dispatcher reads flags into context, then variant decode reads body.

        // willFlag=false branch — optional fields absent on wire, decoded as null
        val noWill = com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<String?>(
            flags = com.ditchoom.buffer.codec.test.protocols.ProbeWillFlags(0x10u), // packetType=1, willFlag=0
            properties = mapOf(1 to 42),
            clientId = "client",
            willProperties = null,
            willTopic = null,
            willPayload = null,
            willTrace = null,
        )
        var buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.encode<String?>(
            buffer = buf,
            value = noWill,
            encodeConnectLikeWillPayload = { wbuf, v -> if (v != null) wbuf.writeString(v) },
        )
        buf.resetForRead()
        val decodedNoWill = com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.decode<String?>(buf) { reader ->
            if (reader.remaining() > 0) reader.copyToBuffer().run { readString(remaining()) } else null
        }
        assertTrue(decodedNoWill is com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<*>)
        assertEquals("client", decodedNoWill.clientId)
        assertEquals(null, decodedNoWill.willTopic)
        assertEquals(null, decodedNoWill.willPayload)
        assertEquals(null, decodedNoWill.willTrace)
        assertEquals(null, decodedNoWill.willProperties)

        // willFlag=true branch — all conditional fields populated
        val withWill = com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<String?>(
            flags = com.ditchoom.buffer.codec.test.protocols.ProbeWillFlags(0x14u), // packetType=1, willFlag=1
            properties = mapOf(1 to 42),
            clientId = "client",
            willProperties = mapOf(2 to 7),
            willTopic = "will/topic",
            willPayload = "payload",
            willTrace = "trace",
        )
        buf = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.encode<String?>(
            buffer = buf,
            value = withWill,
            encodeConnectLikeWillPayload = { wbuf, v -> if (v != null) wbuf.writeString(v) },
        )
        buf.resetForRead()
        val decodedWithWill = com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.decode<String?>(buf) { reader ->
            if (reader.remaining() > 0) reader.copyToBuffer().run { readString(remaining()) } else null
        }
        assertTrue(decodedWithWill is com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<*>)
        assertEquals("client", decodedWithWill.clientId)
        assertEquals("will/topic", decodedWithWill.willTopic)
        assertEquals("payload", decodedWithWill.willPayload)
        assertEquals("trace", decodedWithWill.willTrace)
        assertEquals(mapOf(2 to 7), decodedWithWill.willProperties)
    }
}
