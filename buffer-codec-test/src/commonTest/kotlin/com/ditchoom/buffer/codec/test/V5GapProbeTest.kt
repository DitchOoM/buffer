package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.test.protocols.ProbeByteSizedBytePrefixCollection
import com.ditchoom.buffer.codec.test.protocols.ProbeByteSizedBytePrefixCollectionCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeByteSizedVarintCollection
import com.ditchoom.buffer.codec.test.protocols.ProbeByteSizedVarintCollectionCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeCrossPackageCollectionHost
import com.ditchoom.buffer.codec.test.protocols.ProbeCrossPackageCollectionHostCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeFramedDispatchSimple
import com.ditchoom.buffer.codec.test.protocols.ProbeFramedDispatchSimpleCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeFramedDispatchWithPayload
import com.ditchoom.buffer.codec.test.protocols.ProbeFramedDispatchWithPayloadCodec
import com.ditchoom.buffer.codec.test.protocols.ProbePayloadSliceLifetime
import com.ditchoom.buffer.codec.test.protocols.ProbePayloadSliceLifetimeCodec
import com.ditchoom.buffer.codec.test.protocols.ProbePayloadSlicePostCallback
import com.ditchoom.buffer.codec.test.protocols.ProbePayloadSlicePostCallbackCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeProp
import com.ditchoom.buffer.codec.test.protocols.ProbePropCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarLenBody
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintCollection
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintCollectionCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintListBag
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMaxBytesOverflow
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMaxBytesOverflowCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintNested
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintNestedCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintString
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintStringCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeBytePrefixedPayload
import com.ditchoom.buffer.codec.test.protocols.ProbeBytePrefixedPayloadCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedCollection
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedCollectionCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedNested
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedNestedCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedPayload
import com.ditchoom.buffer.codec.test.protocols.ProbeIntPrefixedPayloadCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeLengthFromString
import com.ditchoom.buffer.codec.test.protocols.ProbeLengthFromStringCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMax1Overflow
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMax1OverflowCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMax3Overflow
import com.ditchoom.buffer.codec.test.protocols.ProbeVarintMax3OverflowCodec
import com.ditchoom.buffer.codec.test.protocols.ProbeZeroCopyEncode
import com.ditchoom.buffer.codec.test.protocols.ProbeZeroCopyEncodeCodec
import com.ditchoom.buffer.codec.test.protocols.crosspkg.CrossPkgEntry
import com.ditchoom.buffer.codec.testRoundTrip
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
        com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec
            .encode(buffer, original)
        buffer.resetForRead()
        val decoded =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec
                .decode(buffer)
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
                bag =
                    com.ditchoom.buffer.codec.test.protocols
                        .ProbePropertyBag(items = emptyList()),
            )
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec
            .encode(buffer, original)
        buffer.resetForRead()
        val decoded =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckShortPrefixedCodec
                .decode(buffer)
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
        val minimal =
            com.ditchoom.buffer.codec.test.protocols
                .ProbeAckOptional(packetId = 1u)
        var buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
            .encode(buf, minimal)
        assertEquals(2, buf.position())
        buf.resetForRead()
        val decodedMin =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
                .decode(buf)
        assertEquals(minimal, decodedMin)

        // Reason code only
        val reasonOnly =
            com.ditchoom.buffer.codec.test.protocols
                .ProbeAckOptional(packetId = 2u, reasonCode = 0x10u)
        buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
            .encode(buf, reasonOnly)
        assertEquals(3, buf.position())
        buf.resetForRead()
        assertEquals(
            reasonOnly,
            com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
                .decode(buf),
        )

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
        com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
            .encode(buf, full)
        buf.resetForRead()
        val decodedFull =
            com.ditchoom.buffer.codec.test.protocols.ProbeAckOptionalCodec
                .decode(buf)
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
                flags =
                    com.ditchoom.buffer.codec.test.protocols
                        .ProbeConnectFlags(0x00u),
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
                flags =
                    com.ditchoom.buffer.codec.test.protocols
                        .ProbeConnectFlags(0x04u),
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
        com.ditchoom.buffer.codec.test.protocols.ProbePropBagPlainCodec
            .encode(plainBuf, plain)
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
        val noWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<String?>(
                flags =
                    com.ditchoom.buffer.codec.test.protocols
                        .ProbeWillFlags(0x10u),
                // packetType=1, willFlag=0
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
        val decodedNoWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.decode<String?>(buf) { reader ->
                if (reader.remaining() > 0) reader.readString(reader.remaining()) else null
            }
        assertTrue(decodedNoWill is com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<*>)
        assertEquals("client", decodedNoWill.clientId)
        assertEquals(null, decodedNoWill.willTopic)
        assertEquals(null, decodedNoWill.willPayload)
        assertEquals(null, decodedNoWill.willTrace)
        assertEquals(null, decodedNoWill.willProperties)

        // willFlag=true branch — all conditional fields populated
        val withWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<String?>(
                flags =
                    com.ditchoom.buffer.codec.test.protocols
                        .ProbeWillFlags(0x14u),
                // packetType=1, willFlag=1
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
        val decodedWithWill =
            com.ditchoom.buffer.codec.test.protocols.ProbeWillTreeCodec.decode<String?>(buf) { reader ->
                if (reader.remaining() > 0) reader.readString(reader.remaining()) else null
            }
        assertTrue(decodedWithWill is com.ditchoom.buffer.codec.test.protocols.ProbeWillTree.ConnectLike<*>)
        assertEquals("client", decodedWithWill.clientId)
        assertEquals("will/topic", decodedWithWill.willTopic)
        assertEquals("payload", decodedWithWill.willPayload)
        assertEquals("trace", decodedWithWill.willTrace)
        assertEquals(mapOf(2 to 7), decodedWithWill.willProperties)
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Phase 1 probes — exercise `LengthPrefix.Varint` length prefixes and `@DispatchOn`
    // body framing via a [DispatchFraming] companion on the discriminator. All probe
    // tests in this section must pass.
    // ─────────────────────────────────────────────────────────────────────────────────

    /** ProbeVarintNested — round-trip at every VBI byte-width boundary. */
    @Test
    fun varintNestedRoundTripsAtAllVbiBoundaries() {
        // Body lengths chosen at every transition + max:
        //   0       → 1-byte VBI (0x00)
        //   127     → 1-byte VBI (0x7F)
        //   128     → 2-byte VBI (0x80 0x01)
        //   16383   → 2-byte VBI (0xFF 0x7F)
        //   16384   → 3-byte VBI (0x80 0x80 0x01)
        //   2097151 → 3-byte VBI (0xFF 0xFF 0x7F)
        //   2097152 → 4-byte VBI (0x80 0x80 0x80 0x01)
        // 268435455 (max) skipped — would allocate ~256 MiB; covered by overflow probe instead.
        val sizes = listOf(0, 127, 128, 16383, 16384, 2097151)
        for (size in sizes) {
            val body = ProbeVarLenBody(data = "a".repeat(size))
            val original = ProbeVarintNested(packetId = 0xBEEFu, body = body)
            val buffer = BufferFactory.Default.allocate(size + 16, ByteOrder.BIG_ENDIAN)
            ProbeVarintNestedCodec.encode(buffer, original)
            val written = buffer.position()
            buffer.resetForRead()
            val decoded = ProbeVarintNestedCodec.decode(buffer)
            assertEquals(original, decoded, "round-trip failed at body size=$size")

            // VBI byte width check via wire size:
            //   total = 2 (packetId) + vbiWidth + size
            val expectedVbiWidth =
                when {
                    size <= 127 -> 1
                    size <= 16383 -> 2
                    size <= 2097151 -> 3
                    else -> 4
                }
            assertEquals(2 + expectedVbiWidth + size, written, "wire size at body size=$size")
        }
    }

    /** ProbeVarintMaxBytesOverflow — encoding a body that needs > maxBytes throws. */
    @Test
    fun varintMaxBytesOverflowThrowsAtEncode() {
        // maxBytes=2 caps prefix to 2-byte VBI (max value 16383). A 16384-byte body needs
        // 3-byte VBI, which exceeds the cap → IllegalArgumentException at encode-time.
        val tooLarge =
            ProbeVarintMaxBytesOverflow(
                packetId = 1u,
                body = ProbeVarLenBody(data = "x".repeat(16384)),
            )
        val buffer = BufferFactory.Default.allocate(20_000, ByteOrder.BIG_ENDIAN)
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ProbeVarintMaxBytesOverflowCodec.encode(buffer, tooLarge)
            }
        // Message must include the field name and the offending length so MQTT users
        // can diagnose `MalformedPacketException`-shaped errors at the source.
        val msg = ex.message.orEmpty()
        assertTrue("body" in msg, "expected field name in error: $msg")
        assertTrue("16384" in msg, "expected offending length in error: $msg")

        // The 16383-byte body should still encode — that's the inclusive upper bound for 2-byte VBI.
        val justFits =
            ProbeVarintMaxBytesOverflow(
                packetId = 2u,
                body = ProbeVarLenBody(data = "x".repeat(16383)),
            )
        val ok = BufferFactory.Default.allocate(20_000, ByteOrder.BIG_ENDIAN)
        ProbeVarintMaxBytesOverflowCodec.encode(ok, justFits)
        ok.resetForRead()
        assertEquals(justFits, ProbeVarintMaxBytesOverflowCodec.decode(ok))
    }

    /** ProbeVarintCollection — VBI byte-length-prefixed list-bearing nested message. */
    @Test
    fun varintCollectionRoundTrip() {
        val original =
            ProbeVarintCollection(
                packetId = 7u,
                bag =
                    ProbeVarintListBag(
                        items =
                            listOf(
                                ProbeProp.UShortProp(0x1234u),
                                ProbeProp.StringPair("k", "v"),
                                ProbeProp.BoolProp(0x01u),
                            ),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        ProbeVarintCollectionCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = ProbeVarintCollectionCodec.decode(buffer)
        assertEquals(7u.toUShort(), decoded.packetId)
        assertEquals(3, decoded.bag.items.size)
        assertEquals(0x1234u.toUShort(), (decoded.bag.items[0] as ProbeProp.UShortProp).value)
        val pair = decoded.bag.items[1] as ProbeProp.StringPair
        assertEquals("k", pair.key)
        assertEquals("v", pair.value)
        assertEquals(0x01u.toUByte(), (decoded.bag.items[2] as ProbeProp.BoolProp).raw)
    }

    /** ProbeVarintString — VBI byte-length prefix on a String. */
    @Test
    fun varintStringRoundTrip() {
        // Round-trip across multiple sizes so the patch-up logic is exercised at each VBI width.
        for (size in listOf(0, 1, 127, 128, 1000)) {
            val original = ProbeVarintString(name = "n".repeat(size))
            val buffer = BufferFactory.Default.allocate(size + 8, ByteOrder.BIG_ENDIAN)
            ProbeVarintStringCodec.encode(buffer, original)
            val written = buffer.position()
            buffer.resetForRead()
            assertEquals(original, ProbeVarintStringCodec.decode(buffer))
            val expectedVbiWidth =
                if (size <= 127) {
                    1
                } else if (size <= 16383) {
                    2
                } else {
                    3
                }
            assertEquals(expectedVbiWidth + size, written, "wire size at string size=$size")
        }
    }

    /** ProbeFramedDispatchSimple — sealed dispatch with VBI-bounded body. */
    @Test
    fun framedDispatchSimpleRoundTrip() {
        for (variant in listOf<ProbeFramedDispatchSimple>(
            ProbeFramedDispatchSimple.Alpha(x = 0xCAFEu),
            ProbeFramedDispatchSimple.Beta(y = 0xDEADBEEFu, z = 0x42u),
            ProbeFramedDispatchSimple.Gamma(message = "hello world"),
        )) {
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            ProbeFramedDispatchSimpleCodec.encode(buffer, variant)
            val written = buffer.position()
            buffer.resetForRead()
            assertEquals(variant, ProbeFramedDispatchSimpleCodec.decode(buffer))

            // Wire format must be [discriminator(1)][VBI(bodyLen)][body].
            // Verify exact byte 0 = discriminator and byte 1 = VBI start.
            buffer.resetForRead()
            val disc = buffer.readByte().toInt() and 0xFF
            val expectedDisc =
                when (variant) {
                    is ProbeFramedDispatchSimple.Alpha -> 0x01
                    is ProbeFramedDispatchSimple.Beta -> 0x02
                    is ProbeFramedDispatchSimple.Gamma -> 0x03
                }
            assertEquals(expectedDisc, disc, "discriminator byte for $variant")
            val bodyLen = buffer.readVariableByteInteger()
            // Total written = 1 (disc) + vbiWidth + bodyLen
            val vbiWidth =
                if (bodyLen <= 127) {
                    1
                } else if (bodyLen <= 16383) {
                    2
                } else {
                    3
                }
            assertEquals(1 + vbiWidth + bodyLen, written)
        }
    }

    /** ProbeFramedDispatchWithPayload — VBI-bounded dispatch + a `<@Payload P>` variant. */
    @Test
    fun framedDispatchWithPayloadRoundTrip() {
        val withBytes =
            ProbeFramedDispatchWithPayload.WithBytes<String>(
                header = 0xAAu,
                topic = "t/x",
                payload = "the-payload",
            )
        val buffer = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        ProbeFramedDispatchWithPayloadCodec.encode<String>(
            buffer = buffer,
            value = withBytes,
            encodeWithBytesPayload = { wbuf, p -> wbuf.writeString(p) },
        )
        buffer.resetForRead()
        val decoded =
            ProbeFramedDispatchWithPayloadCodec.decode<String>(buffer) { reader ->
                reader.readString(reader.remaining())
            }
        assertTrue(decoded is ProbeFramedDispatchWithPayload.WithBytes<*>)
        assertEquals(0xAAu.toUByte(), decoded.header)
        assertEquals("t/x", decoded.topic)
        assertEquals("the-payload", decoded.payload)

        // Sibling non-payload variant must round-trip too — proves VBI patch-up doesn't
        // depend on the presence of a payload reader/writer scope.
        val plain: ProbeFramedDispatchWithPayload = ProbeFramedDispatchWithPayload.Plain(x = 0x1234u)
        val buf2 = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeFramedDispatchWithPayloadCodec.encode<String>(
            buffer = buf2,
            value = plain,
            encodeWithBytesPayload = { _, _ -> fail("payload encoder must not be invoked for Plain") },
        )
        buf2.resetForRead()
        val decodedPlain =
            ProbeFramedDispatchWithPayloadCodec.decode<String>(buf2) { _ ->
                fail("payload decoder must not be invoked for Plain")
            }
        assertEquals(plain, decodedPlain)
    }

    /** ProbeFramedDispatchPeek — peekFrameSize must report total = 1 + vbiWidth + bodyLen. */
    @Test
    fun framedDispatchPeekFrameSize() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool)
        try {
            val variant: ProbeFramedDispatchSimple =
                ProbeFramedDispatchSimple.Gamma(
                    message = "x".repeat(200), // forces 2-byte VBI on body length (~203 bytes)
                )
            val buffer = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
            ProbeFramedDispatchSimpleCodec.encode(buffer, variant)
            val wireSize = buffer.position()
            buffer.resetForRead()

            // Partial: only the discriminator byte present → NeedsMoreData
            val first1 = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
            first1.writeByte(buffer.readByte())
            first1.resetForRead()
            stream.append(first1)
            assertEquals(PeekResult.NeedsMoreData, ProbeFramedDispatchSimpleCodec.peekFrameSize(stream, 0))

            // Append the rest — peek should now resolve to full wire size.
            val rest = BufferFactory.Default.allocate(wireSize - 1, ByteOrder.BIG_ENDIAN)
            while (buffer.remaining() > 0) rest.writeByte(buffer.readByte())
            rest.resetForRead()
            stream.append(rest)
            val peeked = ProbeFramedDispatchSimpleCodec.peekFrameSize(stream, 0)
            assertTrue(peeked is PeekResult.Size, "expected Size, got $peeked")
            assertEquals(wireSize, peeked.bytes)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    /** ProbeFramedDispatchUnderrunOverrun — malformed wire (VBI length mismatches body) throws. */
    @Test
    fun framedDispatchUnderrunOverrunThrows() {
        // VBI claims 5 bytes but Alpha (UShort) only consumes 2 → variant decoder leaves 3
        // unconsumed bytes inside the slice. Dispatcher must detect this and throw.
        val malformedOverrun = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        malformedOverrun.writeByte(0x01) // discriminator: Alpha
        malformedOverrun.writeByte(0x05) // VBI = 5 (1-byte VBI)
        malformedOverrun.writeShort(0x1234.toShort()) // 2 bytes — only fills 2 of the 5
        malformedOverrun.writeByte(0xAA.toByte())
        malformedOverrun.writeByte(0xBB.toByte())
        malformedOverrun.writeByte(0xCC.toByte())
        malformedOverrun.resetForRead()
        val ex1 =
            assertFailsWith<IllegalArgumentException> {
                ProbeFramedDispatchSimpleCodec.decode(malformedOverrun)
            }
        assertNotNull(ex1.message)

        // VBI claims 1 byte but Alpha needs 2 (UShort) → variant decoder underruns.
        val malformedUnderrun = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        malformedUnderrun.writeByte(0x01) // discriminator: Alpha
        malformedUnderrun.writeByte(0x01) // VBI = 1
        malformedUnderrun.writeByte(0x12) // only 1 byte, but Alpha decodes 2
        malformedUnderrun.resetForRead()
        assertFailsWith<Throwable> {
            ProbeFramedDispatchSimpleCodec.decode(malformedUnderrun)
        }
    }

    /**
     * Probe 17 — VBI byte-length prefix on a `Collection<X>` directly.
     * Wire shape: `[VBI(byteSize)][element bytes...]`. Locks in the byte-size + slice
     * semantic — the decoder reads VBI bytes of body, then loops elementCodec.decode
     * until the slice is empty. Round-trip + exact wire bytes for one variant.
     */
    @Test
    fun byteSizedVarintCollectionExactWireBytes() {
        // packetId(2) + VBI(1)=0x05 + BoolProp(2) + UShortProp(3) = 8 bytes total.
        val original =
            ProbeByteSizedVarintCollection(
                packetId = 0x0007u,
                items =
                    listOf(
                        ProbeProp.BoolProp(0xFEu),
                        ProbeProp.UShortProp(0x1234u),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeByteSizedVarintCollectionCodec.encode(buffer, original)
        val written = buffer.position()
        assertEquals(8, written, "wire length: 2 packetId + 1 VBI + 2 BoolProp + 3 UShortProp")

        // Verify exact wire bytes.
        buffer.resetForRead()
        assertEquals(0x00u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x07u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x05u.toUByte(), buffer.readUnsignedByte(), "VBI byte-size = 5")
        assertEquals(0x01u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0xFEu.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x21u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x12u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x34u.toUByte(), buffer.readUnsignedByte())

        // Round-trip.
        buffer.resetForRead()
        val decoded = ProbeByteSizedVarintCollectionCodec.decode(buffer)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(2, decoded.items.size)
        assertEquals(0xFEu.toUByte(), (decoded.items[0] as ProbeProp.BoolProp).raw)
        assertEquals(0x1234u.toUShort(), (decoded.items[1] as ProbeProp.UShortProp).value)
    }

    /**
     * Probe 17b — empty list: VBI(0) = 0x00 single byte. Decoder reads the 0-length
     * slice and produces emptyList() without invoking elementCodec.decode.
     */
    @Test
    fun byteSizedVarintCollectionEmpty() {
        val original = ProbeByteSizedVarintCollection(packetId = 0x0007u, items = emptyList())
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        ProbeByteSizedVarintCollectionCodec.encode(buffer, original)
        val written = buffer.position()
        assertEquals(3, written, "wire: 2 packetId + 1 VBI(0)")

        buffer.resetForRead()
        assertEquals(0x00u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x07u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x00u.toUByte(), buffer.readUnsignedByte())

        buffer.resetForRead()
        val decoded = ProbeByteSizedVarintCollectionCodec.decode(buffer)
        assertEquals(0x0007u.toUShort(), decoded.packetId)
        assertEquals(0, decoded.items.size)
    }

    /**
     * Probe 17c — round-trip across VBI byte-width transitions (1→2 byte boundary at
     * body size 128). Constructs a list of BoolProp items (2 bytes each on the wire)
     * sized to land just under and just over the boundary.
     */
    @Test
    fun byteSizedVarintCollectionVbiBoundaries() {
        // 63 BoolProp items = 126 wire bytes → 1-byte VBI(126) = 0x7E
        // 64 BoolProp items = 128 wire bytes → 2-byte VBI(128) = 0x80 0x01
        for ((count, expectedVbiWidth) in listOf(63 to 1, 64 to 2)) {
            val items = List(count) { ProbeProp.BoolProp(it.toUByte()) }
            val original = ProbeByteSizedVarintCollection(packetId = 0u, items = items)
            val buffer = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
            ProbeByteSizedVarintCollectionCodec.encode(buffer, original)
            val written = buffer.position()
            assertEquals(2 + expectedVbiWidth + count * 2, written, "wire size at count=$count")
            buffer.resetForRead()
            val decoded = ProbeByteSizedVarintCollectionCodec.decode(buffer)
            assertEquals(count, decoded.items.size, "decoded item count at count=$count")
        }
    }

    /**
     * Probe 18 — same byte-size semantic for a fixed-width Byte prefix. Confirms the
     * unified semantic across prefix widths: `@LengthPrefixed(Byte) Collection<X>` is
     * also byte-size + slice. Locks in exact wire bytes so any regression is caught.
     */
    @Test
    fun byteSizedBytePrefixCollectionExactWireBytes() {
        // packetId(2) + Byte-prefix(1)=0x02 + BoolProp(2) = 5 bytes total.
        val original =
            ProbeByteSizedBytePrefixCollection(
                packetId = 0x0007u,
                items = listOf(ProbeProp.BoolProp(0xFEu)),
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeByteSizedBytePrefixCollectionCodec.encode(buffer, original)
        val written = buffer.position()
        assertEquals(5, written, "wire: 2 packetId + 1 Byte-prefix + 2 BoolProp")

        buffer.resetForRead()
        assertEquals(0x00u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x07u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x02u.toUByte(), buffer.readUnsignedByte(), "Byte prefix = 2 bytes (NOT count=1)")
        assertEquals(0x01u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0xFEu.toUByte(), buffer.readUnsignedByte())

        buffer.resetForRead()
        val decoded = ProbeByteSizedBytePrefixCollectionCodec.decode(buffer)
        assertEquals(0x0007u.toUShort(), decoded.packetId)
        assertEquals(1, decoded.items.size)
        assertEquals(0xFEu.toUByte(), (decoded.items[0] as ProbeProp.BoolProp).raw)
    }

    /**
     * Probe 19 — cross-package element codec must be imported into the host codec file.
     * Round-trip is the regression signal — if the import is missing, this test won't
     * compile.
     */
    @Test
    fun crossPackageCollectionElementImports() {
        val original =
            ProbeCrossPackageCollectionHost(
                tag = 0x0Au,
                items =
                    listOf(
                        CrossPkgEntry(0x01u, "alpha"),
                        CrossPkgEntry(0x02u, "beta"),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeCrossPackageCollectionHostCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = ProbeCrossPackageCollectionHostCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Phase 3.0 probes — `@Payload P` decode lambda receives a `ReadBuffer` slice.
    //
    // Pre-Phase-3.1 the generated lambda parameter type is `PayloadReader`; these tests
    // pass `(ReadBuffer) -> P` lambdas, which fail to compile against the pre-3.1
    // signature. After Phase 3.1 deletes `PayloadReader` and re-types the lambdas, all
    // three pass.
    // ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Probe 20 — decode lambda receives a `ReadBuffer` slice; bytes match input exactly
     * with no intermediate codec-internal allocation.
     */
    @Test
    fun payloadDecodeLambdaReceivesReadBufferSlice() {
        val payload = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        payload.writeInt(0xDEADBEEF.toInt())
        payload.writeInt(0xCAFEBABE.toInt())
        payload.resetForRead()

        val original = ProbePayloadSliceLifetime<ReadBuffer>(tag = 0xABu, payload = payload)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbePayloadSliceLifetimeCodec.encode<ReadBuffer>(
            buffer,
            original,
            encodePayload = { buf, p -> buf.write(p) },
        )
        buffer.resetForRead()

        var observedRemaining = -1
        val decoded =
            ProbePayloadSliceLifetimeCodec.decode<ReadBuffer>(
                buffer,
                decodePayload = { slice ->
                    observedRemaining = slice.remaining()
                    slice.readBytes(slice.remaining())
                },
            )
        assertEquals(0xABu.toUByte(), decoded.tag)
        assertEquals(8, observedRemaining)

        // The slice returned by `readBytes(...)` is already positioned for reading
        // (position=0, limit=size); no `resetForRead` flip is needed.
        assertEquals(0xDEADBEEF.toInt(), decoded.payload.readInt())
        assertEquals(0xCAFEBABE.toInt(), decoded.payload.readInt())
    }

    /**
     * Probe 21 — encode lambda forwards a `ReadBuffer` payload via a single
     * `buf.write(slice)` call. Round-trips end-to-end with no intermediate copy on the
     * encode side beyond the irreducible slice → wire memcpy.
     */
    @Test
    fun payloadEncodeLambdaWritesSliceDirectly() {
        val payload = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        payload.writeLong(0x0123456789ABCDEFL)
        payload.writeLong(-0x123456789abcdf0L) // 0xFEDCBA9876543210 as signed Long
        payload.resetForRead()

        val original = ProbeZeroCopyEncode<ReadBuffer>(tag = 0x42u, payload = payload)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeZeroCopyEncodeCodec.encode<ReadBuffer>(
            buffer,
            original,
            encodePayload = { buf, slice -> buf.write(slice) },
        )

        buffer.resetForRead()
        val decoded =
            ProbeZeroCopyEncodeCodec.decode<ReadBuffer>(
                buffer,
                decodePayload = { slice -> slice.readBytes(slice.remaining()) },
            )
        assertEquals(0x42u.toUByte(), decoded.tag)

        assertEquals(0x0123456789ABCDEFL, decoded.payload.readLong())
        assertEquals(-0x123456789abcdf0L, decoded.payload.readLong())
    }

    /**
     * Probe 22 — caller retains the slice past the callback scope. With a non-pooled
     * source buffer (allocated via `BufferFactory.Default`), the slice's underlying
     * memory remains alive after the lambda returns. Reading from the retained slice
     * yields the original bytes.
     */
    @Test
    fun payloadSliceRemainsReadableAfterCallbackForNonPooledSource() {
        val payload = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        payload.writeInt(0x55AA55AA)
        payload.writeInt(-0x55aa55ab) // 0xAA55AA55 as signed Int
        payload.resetForRead()

        val original = ProbePayloadSlicePostCallback<ReadBuffer>(tag = 0x99u, payload = payload)
        val source = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbePayloadSlicePostCallbackCodec.encode<ReadBuffer>(
            source,
            original,
            encodePayload = { buf, p -> buf.write(p) },
        )
        source.resetForRead()

        var retainedSlice: ReadBuffer? = null
        val decoded =
            ProbePayloadSlicePostCallbackCodec.decode<ReadBuffer>(
                source,
                decodePayload = { slice ->
                    retainedSlice = slice
                    slice.readBytes(slice.remaining())
                },
            )
        assertEquals(0x99u.toUByte(), decoded.tag)

        // The retained slice still points into live memory because the source buffer is
        // alive. The slice's own position has been advanced by `slice.readBytes(...)`
        // inside the callback; resetting it for read confirms the underlying bytes are
        // still readable.
        val retained = assertNotNull(retainedSlice)
        retained.resetForRead()
        assertEquals(0x55AA55AA, retained.readInt())
        assertEquals(-0x55aa55ab, retained.readInt())
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // wireSize codegen coverage tests — exercises gaps in the wireSize emit path.
    //
    // testRoundTrip(...) calls assertEquals(wireSize(value), encoded.remaining()), so
    // round-tripping any fixture through it doubles as a wireSize correctness check.
    // ─────────────────────────────────────────────────────────────────────────────────

    /** Varint maxBytes=1 cap-overflow — 128-byte body throws, 127-byte body encodes. */
    @Test
    fun varintMax1OverflowThrowsAtEncode() {
        val tooLarge =
            ProbeVarintMax1Overflow(
                packetId = 1u,
                body = ProbeVarLenBody(data = "x".repeat(128)),
            )
        val buffer = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ProbeVarintMax1OverflowCodec.encode(buffer, tooLarge)
            }
        val msg = ex.message.orEmpty()
        assertTrue("body" in msg, "expected field name in error: $msg")
        assertTrue("128" in msg, "expected offending length in error: $msg")

        // 127-byte body fits in 1-byte VBI cap.
        val justFits =
            ProbeVarintMax1Overflow(
                packetId = 2u,
                body = ProbeVarLenBody(data = "x".repeat(127)),
            )
        val ok = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
        ProbeVarintMax1OverflowCodec.encode(ok, justFits)
        ok.resetForRead()
        assertEquals(justFits, ProbeVarintMax1OverflowCodec.decode(ok))
    }

    /** Varint maxBytes=3 cap-overflow — 2097152-byte body throws, 2097151-byte body encodes. */
    @Test
    fun varintMax3OverflowThrowsAtEncode() {
        val tooLarge =
            ProbeVarintMax3Overflow(
                packetId = 1u,
                body = ProbeVarLenBody(data = CharArray(2097152) { 'x' }.concatToString()),
            )
        val buffer = BufferFactory.Default.allocate(2_500_000, ByteOrder.BIG_ENDIAN)
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ProbeVarintMax3OverflowCodec.encode(buffer, tooLarge)
            }
        val msg = ex.message.orEmpty()
        assertTrue("body" in msg, "expected field name in error: $msg")
        assertTrue("2097152" in msg, "expected offending length in error: $msg")

        // 2097151-byte body fits in 3-byte VBI cap.
        val justFits =
            ProbeVarintMax3Overflow(
                packetId = 2u,
                body = ProbeVarLenBody(data = CharArray(2097151) { 'x' }.concatToString()),
            )
        val ok = BufferFactory.Default.allocate(2_500_000, ByteOrder.BIG_ENDIAN)
        ProbeVarintMax3OverflowCodec.encode(ok, justFits)
        ok.resetForRead()
        assertEquals(justFits, ProbeVarintMax3OverflowCodec.decode(ok))
    }

    /** `@LengthPrefixed(LengthPrefix.Int)` fixed 4-byte prefix on a NestedWithLength field. */
    @Test
    fun intPrefixedNestedRoundTrip() {
        val original =
            ProbeIntPrefixedNested(
                packetId = 0xBEEFu,
                body = ProbeVarLenBody(data = "the-body"),
            )
        val decoded = ProbeIntPrefixedNestedCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    /** `@LengthPrefixed(LengthPrefix.Int)` fixed 4-byte prefix on a Collection-bearing nested message. */
    @Test
    fun intPrefixedCollectionRoundTrip() {
        val original =
            ProbeIntPrefixedCollection(
                packetId = 0x1234u,
                bag =
                    com.ditchoom.buffer.codec.test.protocols.ProbeVarintListBag(
                        items =
                            listOf(
                                ProbeProp.UShortProp(0xAAAAu),
                                ProbeProp.BoolProp(0x01u),
                            ),
                    ),
            )
        val decoded = ProbeIntPrefixedCollectionCodec.testRoundTrip(original)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(2, decoded.bag.items.size)
        assertEquals(0xAAAAu.toUShort(), (decoded.bag.items[0] as ProbeProp.UShortProp).value)
        assertEquals(0x01u.toUByte(), (decoded.bag.items[1] as ProbeProp.BoolProp).raw)
    }

    /** `@Payload P` field with Byte-prefixed length — wireSize formula: tag(1) + 1 + sizePayload(payload). */
    @Test
    fun bytePrefixedPayloadRoundTrip() {
        val original = ProbeBytePrefixedPayload<String>(tag = 0xAAu, payload = "hi")
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeBytePrefixedPayloadCodec.encode<String>(
            buffer,
            original,
            encodePayload = { buf, s -> buf.writeString(s) },
        )
        val written = buffer.position()
        val sized = ProbeBytePrefixedPayloadCodec.wireSize<String>(original) { it.length }
        assertEquals(written, sized, "wireSize must match encoded byte count")
        buffer.resetForRead()
        val decoded =
            ProbeBytePrefixedPayloadCodec.decode<String>(
                buffer,
                decodePayload = { reader -> reader.readString(reader.remaining()) },
            )
        assertEquals(original.tag, decoded.tag)
        assertEquals(original.payload, decoded.payload)
    }

    /** `@Payload P` field with Int-prefixed length — wireSize formula: tag(1) + 4 + sizePayload(payload). */
    @Test
    fun intPrefixedPayloadRoundTrip() {
        val original = ProbeIntPrefixedPayload<String>(tag = 0x55u, payload = "intpayload")
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ProbeIntPrefixedPayloadCodec.encode<String>(
            buffer,
            original,
            encodePayload = { buf, s -> buf.writeString(s) },
        )
        val written = buffer.position()
        val sized = ProbeIntPrefixedPayloadCodec.wireSize<String>(original) { it.length }
        assertEquals(written, sized, "wireSize must match encoded byte count")
        buffer.resetForRead()
        val decoded =
            ProbeIntPrefixedPayloadCodec.decode<String>(
                buffer,
                decodePayload = { reader -> reader.readString(reader.remaining()) },
            )
        assertEquals(original.tag, decoded.tag)
        assertEquals(original.payload, decoded.payload)
    }

    /** `@LengthFrom("nameLen")` on a String field. */
    @Test
    fun lengthFromStringRoundTrip() {
        val original = ProbeLengthFromString(nameLen = 5u, name = "hello")
        val decoded = ProbeLengthFromStringCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    /**
     * Sealed-dispatcher payload-variant wireSize without context must error pointing at
     * the missing SizeKey. The dispatcher delegates payload variants to
     * `wireSizeFromContext`, which fails when the caller hasn't registered the size
     * lambda — the error message names the variant codec and the SizeKey to register.
     * Uses [com.ditchoom.buffer.codec.test.protocols.DispatchedFrameCodec], which has a
     * `Data<*>` payload-bearing variant and a non-payload `Control` sibling.
     */
    @Test
    fun sealedDispatcherPayloadVariantWireSizeErrors() {
        val payloadVariant: com.ditchoom.buffer.codec.test.protocols.DispatchedFrame =
            com.ditchoom.buffer.codec.test.protocols.DispatchedFrame.Data(
                header =
                    com.ditchoom.buffer.codec.test.protocols
                        .FrameHeader(0x01u, 0x00u),
                payload = "hello",
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                com.ditchoom.buffer.codec.test.protocols.DispatchedFrameCodec.wireSize(payloadVariant)
            }
        val msg = ex.message.orEmpty()
        assertTrue(
            "DispatchedFrameDataCodec" in msg,
            "expected variant codec name in error: $msg",
        )
        assertTrue(
            "SizeKey" in msg,
            "expected SizeKey reference in error so caller knows what to register: $msg",
        )
    }
}
