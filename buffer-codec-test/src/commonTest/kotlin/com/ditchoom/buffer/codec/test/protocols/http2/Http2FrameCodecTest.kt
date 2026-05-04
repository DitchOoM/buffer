package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Stage F slice 6.5 + Stage H slice 10d doctrine vector. Exercises
 * the bit-packed `@DispatchOn(Http2LengthAndType::class)` dispatcher
 * with a 4-byte (UInt) discriminator big-endian per RFC 7540 §4.1.
 *
 * Slice 10d lifts the dispatcher to a generic class — the parent is
 * `Http2Frame<out P : Payload>`, the codec is `class Http2FrameCodec
 * <P : Payload>(payloadCodec: Codec<P>) : Codec<Http2Frame<P>>`. The
 * payload-free variants (`Settings`, `Ping`, `WindowUpdate`) are
 * `: Http2Frame<Nothing>`; the new `Data<P : Payload>` variant
 * (RFC §6.1) carries the typed payload.
 *
 * Test choice for `payloadCodec`: most tests use
 * `Http2BinaryPayloadCodec` since the choice is irrelevant for
 * payload-free variants (covariance of `out P` makes
 * `Http2Frame<Nothing>` assignable to `Http2Frame<Http2Binary
 * Payload>`). Data-variant tests instantiate the dispatcher per
 * payload type to confirm the codec routing is type-correct.
 */
class Http2FrameCodecTest {
    @Test
    fun lengthAndTypeExtractsPackedFields() {
        val h = Http2LengthAndType.of(length = 8, type = 6)
        assertEquals(8, h.length)
        assertEquals(6, h.type)
        // Packed wire form: top 24 bits = length, low 8 bits = type.
        assertEquals(0x00_00_08_06u, h.raw)
    }

    @Test
    fun encodesPingByteExact() {
        // RFC 7540 §6.7: PING is type=6, length=8, streamId=0, 8-byte opaque payload.
        val msg =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                opaqueData = 0xDEAD_BEEF_CAFE_F00DuL,
            )
        val expected =
            byteArrayOf(
                // Header: length=8 (24-bit BE), type=6
                0x00, 0x00, 0x08, 0x06,
                // Flags
                0x00,
                // StreamId (UInt BE), 0
                0x00, 0x00, 0x00, 0x00,
                // Opaque data (ULong BE)
                0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
                0xCA.toByte(), 0xFE.toByte(), 0xF0.toByte(), 0x0D.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesWindowUpdateByteExact() {
        // RFC 7540 §6.9: WINDOW_UPDATE is type=8, length=4.
        val msg =
            Http2Frame.WindowUpdate(
                header = Http2LengthAndType.of(length = 4, type = 8),
                flags = 0x00u,
                streamId = Http2StreamId(1u),
                windowSizeIncrement = 65535u,
            )
        val expected =
            byteArrayOf(
                // Header: length=4 (24-bit BE), type=8
                0x00, 0x00, 0x04, 0x08,
                0x00,
                // StreamId (UInt BE) = 1
                0x00, 0x00, 0x00, 0x01,
                // windowSizeIncrement (UInt BE) = 65535
                0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesPingFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x08, 0x06,
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78,
                0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = binaryDispatcher().decode(buf, DecodeContext.Empty)
        val ping = assertIs<Http2Frame.Ping>(decoded)
        assertEquals(8, ping.header.length)
        assertEquals(6, ping.header.type)
        assertEquals(0x00u.toUByte(), ping.flags)
        assertEquals(Http2StreamId(0u), ping.streamId)
        assertEquals(0x1234_5678_9ABC_DEF0uL, ping.opaqueData)
    }

    @Test
    fun decodesWindowUpdateFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x04, 0x08,
                0x00,
                0x00, 0x00, 0x00, 0x05,
                0x00, 0x00, 0x10, 0x00,
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = binaryDispatcher().decode(buf, DecodeContext.Empty)
        val wu = assertIs<Http2Frame.WindowUpdate>(decoded)
        assertEquals(4, wu.header.length)
        assertEquals(8, wu.header.type)
        assertEquals(Http2StreamId(5u), wu.streamId)
        assertEquals(4096u, wu.windowSizeIncrement)
    }

    @Test
    fun http2StreamIdRejectsHighBitOnConstruction() {
        // Per RFC 7540 §4.1, the reserved `R` bit MUST be 0 on send.
        // The Http2StreamId value class enforces this at construction
        // time so the encoded bytes are spec-compliant by virtue of
        // the field's type.
        assertFailsWith<IllegalArgumentException> {
            Http2StreamId(0x80000000u)
        }
        // Boundary: 0x7FFFFFFF (max 31-bit value) is legal.
        assertEquals(0x7FFFFFFFu, Http2StreamId(0x7FFFFFFFu).raw)
    }

    @Test
    fun decodeThrowsWhenWireBytesViolateRBit() {
        val wire =
            byteArrayOf(
                // Header: length=8 type=6 (PING)
                0x00, 0x00, 0x08, 0x06,
                0x00,
                // streamId with R bit set: 0x80_00_00_00
                0x80.toByte(), 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            )
        val buf = bigEndianBufferOf(wire)
        assertFailsWith<IllegalArgumentException> {
            binaryDispatcher().decode(buf, DecodeContext.Empty)
        }
    }

    @Test
    fun decodeThrowsOnUnknownDispatchValue() {
        // Type 7 (GOAWAY) — not in our sealed set.
        val wire = byteArrayOf(0x00, 0x00, 0x00, 0x07)
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                binaryDispatcher().decode(buf, DecodeContext.Empty)
            }
        assertEquals("Http2Frame.discriminator", ex.fieldPath)
    }

    @Test
    fun roundTripsPing() {
        val original =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0x01u,
                streamId = Http2StreamId(0u),
                opaqueData = 0x0102_0304_0506_0708uL,
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, binaryDispatcher().decode(buf, DecodeContext.Empty))
    }

    @Test
    fun encodesSettingsVariantByteExact() {
        // SETTINGS frame per RFC 7540 §6.5 with two entries.
        // length = 12 (two 6-byte entries), type = 4 (SETTINGS).
        val msg =
            Http2Frame.Settings(
                header = Http2LengthAndType.of(length = 12, type = 4),
                flags = 0u,
                streamId = Http2StreamId(0u),
                entries =
                    listOf(
                        Http2Setting(identifier = 0x0003u, value = 100u), // MAX_CONCURRENT_STREAMS
                        Http2Setting(identifier = 0x0004u, value = 65535u), // INITIAL_WINDOW_SIZE
                    ),
            )
        val expected =
            byteArrayOf(
                // Header: length=12 (24-bit BE) + type=4
                0x00, 0x00, 0x0C, 0x04,
                // Flags
                0x00,
                // StreamId = 0
                0x00, 0x00, 0x00, 0x00,
                // Entry 1: identifier=3, value=100
                0x00, 0x03, 0x00, 0x00, 0x00, 0x64,
                // Entry 2: identifier=4, value=65535
                0x00, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesSettingsVariantViaDispatcher() {
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x0C, 0x04,
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x03, 0x00, 0x00, 0x00, 0x64,
                0x00, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = binaryDispatcher().decode(buf, DecodeContext.Empty)
        val settings = assertIs<Http2Frame.Settings>(decoded)
        assertEquals(12, settings.header.length)
        assertEquals(4, settings.header.type)
        assertEquals(2, settings.entries.size)
        assertEquals(Http2Setting(0x0003u, 100u), settings.entries[0])
        assertEquals(Http2Setting(0x0004u, 65535u), settings.entries[1])
    }

    @Test
    fun roundTripsSettingsViaDispatcher() {
        val original =
            Http2Frame.Settings(
                header = Http2LengthAndType.of(length = 18, type = 4),
                flags = 0u,
                streamId = Http2StreamId(0u),
                entries =
                    listOf(
                        Http2Setting(identifier = 0x0001u, value = 4096u),
                        Http2Setting(identifier = 0x0005u, value = 16384u),
                        Http2Setting(identifier = 0x0006u, value = 0xFFFFFFFFu),
                    ),
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, binaryDispatcher().decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeSettingsRespectsHeaderLengthBound() {
        // Wire has 6-byte payload but trailing bytes follow — decode
        // must stop at header.length=6 (one entry) and leave the rest.
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x06, 0x04,
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x03, 0x00, 0x00, 0x00, 0x64,
                // Trailing bytes (next frame's header)
                0x00, 0x00, 0x08, 0x06,
            )
        val buf = bigEndianBufferOf(wire)
        val settings = assertIs<Http2Frame.Settings>(binaryDispatcher().decode(buf, DecodeContext.Empty))
        assertEquals(1, settings.entries.size, "decode bounded by header.length, not buffer remaining")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next frame")
    }

    @Test
    fun roundTripsWindowUpdate() {
        val original =
            Http2Frame.WindowUpdate(
                header = Http2LengthAndType.of(length = 4, type = 8),
                flags = 0x00u,
                streamId = Http2StreamId(12345u),
                windowSizeIncrement = 0x7FFF_FFFFu,
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, binaryDispatcher().decode(buf, DecodeContext.Empty))
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenLessThanDiscriminator() {
        // Need at least 4 bytes (the discriminator's wire width) to identify a variant.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, binaryDispatcher().peekFrameSize(stream))

            // Three bytes — still not enough.
            val three = BufferFactory.Default.allocate(3)
            three.writeByte(0x00.toByte())
            three.writeByte(0x00.toByte())
            three.writeByte(0x08.toByte())
            three.resetForRead()
            stream.append(three)
            assertEquals(PeekResult.NeedsMoreData, binaryDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksDripFedPing() {
        val pool = BufferPool()
        val original =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0u,
                streamId = Http2StreamId(0u),
                opaqueData = 0u,
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        // Sanity: 4 (header) + 1 (flags) + 4 (streamId) + 8 (opaque) = 17.
        assertEquals(17, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    binaryDispatcher().peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), binaryDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    // ----- Slice 10d Data variant via the generic dispatcher -----

    @Test
    fun roundTripsDataVariantViaDispatcherWithBinaryPayload() {
        // Sanity: the absorbed Http2DataFrame round-trip survives the
        // sealed-parent integration. The dispatcher routes the typed
        // payload codec through to the variant.
        val original =
            Http2Frame.Data<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 4, type = 0),
                flags = 0x01u, // END_STREAM bit, codec-irrelevant
                streamId = Http2StreamId(7u),
                payload = Http2BinaryPayload(byteArrayOf(0x01, 0x02, 0x03, 0x04)),
            )
        val codec = Http2FrameCodec(Http2BinaryPayloadCodec)
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        val written = buf.position()
        // 4 (header) + 1 (flags) + 4 (streamId) + 4 (payload) = 13
        assertEquals(13, written)
        buf.resetForRead()
        // Caller bounds the buffer to the frame's byte count — a
        // future slice will lift this so the dispatcher derives the
        // bound from header.length.
        buf.setLimit(written)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun encodesDataVariantByteExact() {
        // RFC §6.1 shape: header / flags / streamId / payload bytes.
        val codec = Http2FrameCodec(Http2BinaryPayloadCodec)
        val msg =
            Http2Frame.Data<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 3, type = 0),
                flags = 0u,
                streamId = Http2StreamId(1u),
                payload = Http2BinaryPayload(byteArrayOf(0x11, 0x22, 0x33)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, msg, EncodeContext.Empty)
        buf.resetForRead()
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                // header: length=3 (24 bits BE) | type=0
                0x00, 0x00, 0x03, 0x00,
                0x00,
                // streamId
                0x00, 0x00, 0x00, 0x01,
                // payload bytes
                0x11, 0x22, 0x33,
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun roundTripsDataVariantWithOpaquePayload() {
        // Same dispatcher class instantiated for a different payload
        // type — confirms generic-aware dispatch isn't accidentally
        // tied to the Http2BinaryPayload type.
        val original =
            Http2Frame.Data<Http2OpaquePayload>(
                header = Http2LengthAndType.of(length = 8, type = 0),
                flags = 0u,
                streamId = Http2StreamId(0xCAFEu),
                payload = Http2OpaquePayload(0x0123_4567_89AB_CDEFu),
            )
        val codec = Http2FrameCodec(Http2OpaquePayloadCodec)
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        val written = buf.position()
        buf.resetForRead()
        buf.setLimit(written)
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun dataVariantWireSizeForwardsToVariantCodec() {
        // The dispatcher's wireSize forwards to the variant codec,
        // which is BackPatch for any RemainingBytesPayload-bearing
        // shape. Confirms the generic dispatcher's wireSize path
        // correctly routes through the constructed variant codec
        // instance (not a static reference).
        val codec = Http2FrameCodec(Http2BinaryPayloadCodec)
        val msg =
            Http2Frame.Data<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 0, type = 0),
                flags = 0u,
                streamId = Http2StreamId(0u),
                payload = Http2BinaryPayload(ByteArray(0)),
            )
        assertEquals(WireSize.BackPatch, codec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun nothingVariantsRoundTripUnderAnyPayloadInstantiation() {
        // Http2Frame.Settings : Http2Frame<Nothing> assigns to
        // Http2Frame<JpegImage> via covariance. Confirm encode +
        // decode under an arbitrary payload codec instantiation
        // doesn't try to invoke the payload codec.
        val opaqueDispatcher = Http2FrameCodec(Http2OpaquePayloadCodec)
        val original =
            Http2Frame.WindowUpdate(
                header = Http2LengthAndType.of(length = 4, type = 8),
                flags = 0u,
                streamId = Http2StreamId(1u),
                windowSizeIncrement = 1024u,
            )
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        opaqueDispatcher.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(original, opaqueDispatcher.decode(buf, DecodeContext.Empty))
    }

    private fun encodeAndAssertBytes(
        msg: Http2Frame<*>,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches RFC 7540 §4.1 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match RFC 7540 §4.1")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    // For payload-free variants, the payload codec choice is
    // irrelevant — the dispatcher never invokes it. We use the
    // binary payload codec (cheapest fixture) by convention.
    @Suppress("UNCHECKED_CAST")
    private fun encode(value: Http2Frame<*>) =
        BufferFactory.Default
            .allocate(256, ByteOrder.BIG_ENDIAN)
            .also {
                (binaryDispatcher() as Codec<Http2Frame<*>>)
                    .encode(it, value, EncodeContext.Empty)
            }

    private fun binaryDispatcher(): Http2FrameCodec<Http2BinaryPayload> =
        Http2FrameCodec(Http2BinaryPayloadCodec)
}

/** Minimal binary `Payload` for the slice 10d HTTP/2 vector. */
internal data class Http2BinaryPayload(
    val data: ByteArray,
) : Payload {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Http2BinaryPayload) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

internal object Http2BinaryPayloadCodec : Codec<Http2BinaryPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http2BinaryPayload = Http2BinaryPayload(buffer.readByteArray(buffer.remaining()))

    override fun encode(
        buffer: WriteBuffer,
        value: Http2BinaryPayload,
        context: EncodeContext,
    ) {
        buffer.writeBytes(value.data)
    }

    override fun wireSize(
        value: Http2BinaryPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.data.size)
}

/**
 * Second `Payload` shape — a fixed-size opaque ULong, like the PING
 * frame body. Distinct from `Http2BinaryPayload` so tests can confirm
 * the generic dispatcher works across payload types within HTTP/2.
 */
internal data class Http2OpaquePayload(
    val value: ULong,
) : Payload

internal object Http2OpaquePayloadCodec : Codec<Http2OpaquePayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http2OpaquePayload = Http2OpaquePayload(buffer.readULong())

    override fun encode(
        buffer: WriteBuffer,
        value: Http2OpaquePayload,
        context: EncodeContext,
    ) {
        buffer.writeULong(value.value)
    }

    override fun wireSize(
        value: Http2OpaquePayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(8)
}
