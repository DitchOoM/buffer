package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural assertions that pin shape-level invariants for every Plan IR
 * shape — covering all 9 shapes from the rearchitecture plan's per-shape
 * catalog, plus the canonical 8 codec list. Each test asserts properties of
 * the emitted source that must hold regardless of formatting (variant
 * declaration order, e.g.).
 *
 * Pure-snapshot equality lives in [PinnedSnapshotTest] for the four most
 * stable shapes; this file's assertions cover every shape and keep the suite
 * green even if cosmetic changes shift line breaks or the precise variant
 * order in a `when` arm.
 */
class StructuralEmitterTest {
    private val emitter = CodecEmitter(EmitterFixtures.standardRegistry())

    private fun emit(
        plan: Plan,
        simple: String,
    ): String = emitter.emit(plan, EmitterFixtures.cn(simple)).toString()

    // ---------------------------------------------------------------------
    // Shape 1 — Plan.Leaf fixed-width primary ctor (MqttFixedHeader)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape1 fixed-width Plan_Leaf emits const literal wireSize`() {
        val s = emit(EmitterFixtures.mqttFixedHeader(), "MqttFixedHeader")
        assertTrue(s.contains("public const val MIN_HEADER_BYTES: Int = 1"))
        assertTrue(s.contains("override fun wireSize(`value`: MqttFixedHeader, context: EncodeContext): Int = 1"))
        // Banned-pattern: no `var _size = 0; _size += 1; return _size` for fixed-width.
        assertTrue(!s.contains("var _size = 0; _size += 1; return _size"))
        assertTrue(!s.contains("var size = 0\n  size += 1\n  return size"))
    }

    @Test
    fun `Shape1 fixed-width emits inline ctor when single primitive field`() {
        val s = emit(EmitterFixtures.mqttFixedHeader(), "MqttFixedHeader")
        // Expression-body decode: `decode(...): T = T(buffer.read…())`.
        assertTrue(s.contains("MqttFixedHeader(buffer.readUnsignedByte())"))
    }

    // ---------------------------------------------------------------------
    // Shape 2 — Plan.Leaf fixed prefix + tail buffer slice (gRPC frame)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape2 fixed prefix + tail emits expression-fold wireSize`() {
        val s = emit(EmitterFixtures.grpcFrame(), "GrpcFrame")
        assertTrue(s.contains("Int = 5 + value.body.remaining()"), "Expected expression-fold wireSize but got:\n$s")
    }

    // ---------------------------------------------------------------------
    // Shape 3 — Plan.Leaf conditional fields (MQTT v5 PubAck)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape3 conditional fields emits remaining-gte guard on decode`() {
        val s = emit(EmitterFixtures.mqttPubAck(), "MqttPubAck")
        assertTrue(s.contains("if (buffer.remaining() >= 1)"), "Expected remaining-gte guard on conditional decode but got:\n$s")
    }

    @Test
    fun `Shape3 conditional fields encodes via smart-cast not double-bang`() {
        val s = emit(EmitterFixtures.mqttPubAck(), "MqttPubAck")
        // Banned: `value.cond1!!`. We emit a local + null check + smart-cast use.
        assertTrue(!s.contains("value.reasonCode!!"))
        assertTrue(s.contains("val reasonCode = value.reasonCode"))
        assertTrue(s.contains("if (reasonCode != null)"))
    }

    @Test
    fun `Shape3 conditional fields uses accumulator wireSize`() {
        val s = emit(EmitterFixtures.mqttPubAck(), "MqttPubAck")
        assertTrue(s.contains("var size = 0"), "Expected accumulator wireSize for conditional fields but got:\n$s")
    }

    // ---------------------------------------------------------------------
    // Shape 4 — Plan.Leaf batched bit-extraction (MQTT v3 ConnectFlags)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape4 batched bit-extraction emits single masked-shift read`() {
        val s = emit(EmitterFixtures.mqttConnectFlags(), "MqttConnectFlags")
        assertTrue(s.contains("val bits = buffer.readUnsignedByte().toInt() and 0xFF"))
        assertTrue(s.contains("(bits and 0x4) != 0"))
        assertTrue(s.contains("((bits ushr 3) and 0x3)"))
    }

    @Test
    fun `Shape4 batched wireSize is const literal`() {
        val s = emit(EmitterFixtures.mqttConnectFlags(), "MqttConnectFlags")
        assertTrue(s.contains("Int = 1"), "Expected const-literal wireSize for batched MqttConnectFlags but got:\n$s")
    }

    // ---------------------------------------------------------------------
    // Shape 5 — Plan.Object_ singleton (MQTT v4 PingResponse)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape5 singleton emits zero MIN_HEADER_BYTES and zero wireSize`() {
        val s = emit(EmitterFixtures.pingResponse(), "PingResponse")
        assertTrue(s.contains("public const val MIN_HEADER_BYTES: Int = 0"))
        assertTrue(s.contains("Int = 0"))
        assertTrue(s.contains("PeekResult.Size(0)"))
        assertTrue(s.contains(": PingResponse = PingResponse"))
    }

    // ---------------------------------------------------------------------
    // Shape 6 — Plan.Sealed_ Unframed (RIFF chunk)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape6 unframed sealed emits when over discriminator`() {
        val s = emit(EmitterFixtures.riffChunk(), "RiffChunk")
        assertTrue(s.contains("RiffChunkIdCodec.decode(buffer, context)"))
        assertTrue(s.contains("when (type)"))
        assertTrue(s.contains("1_684_108_385")) // RIFF data chunk magic.
        // No DiscriminatorKey emitted — no variant has DiscriminatorOwned field.
        assertTrue(!s.contains("CodecContext.Key"))
    }

    // ---------------------------------------------------------------------
    // Shape 7 — Plan.Sealed_ PeekOnly (WebSocket frame)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape7 peek-only sealed delegates to framer's peekFrameSize`() {
        val s = emit(EmitterFixtures.wsFrame(), "WsFrame")
        assertTrue(s.contains("WsFraming.peekFrameSize(stream, baseOffset)"))
        // Variants dispatched on `byte1.opcode`.
        assertTrue(s.contains("discriminator.byte1.opcode"))
    }

    // ---------------------------------------------------------------------
    // Shape 8 — Plan.Sealed_ BodyLength (MQTT control packet)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape8 body-length sealed slices body and guards trailing bytes`() {
        val s = emit(EmitterFixtures.controlPacketV5(), "ControlPacketV5")
        assertTrue(s.contains("MqttFixedHeader.readBodyLength(buffer)"))
        // Slice 5.5: body-length locals renamed to legacy convention `_bodyLen` /
        // `_bodySlice` so the body-overrun guard reads identically to legacy.
        assertTrue(s.contains("buffer.readBytes(_bodyLen)"))
        assertTrue(s.contains("_bodySlice.remaining() != 0"))
        assertTrue(s.contains("CodecContext.Key<MqttFixedHeader>"))
    }

    @Test
    fun `Shape8 body-length sealed emits ranged + point arms in same when`() {
        val s = emit(EmitterFixtures.controlPacketV5(), "ControlPacketV5")
        // Range arm via rawByte, point arm via type.
        assertTrue(s.contains("rawByte in 48..63"))
        assertTrue(s.contains("type == 4"))
        assertTrue(s.contains("type == 12"))
    }

    // ---------------------------------------------------------------------
    // Shape 9 — Plan.Sealed_ WithPayload (MQTT v5 PUBLISH framed)
    // ---------------------------------------------------------------------

    @Test
    fun `Shape9 WithPayload variant routes to decodeFromContext`() {
        val s = emit(EmitterFixtures.controlPacketV5(), "ControlPacketV5")
        // Slice 5.5: body-length locals renamed to legacy convention `_bodySlice`.
        assertTrue(s.contains("ControlPacketV5PublishCodec.decodeFromContext(_bodySlice, ctx)"))
        // Encode also goes via encodeFromContext for WithPayload variants.
        assertTrue(s.contains("ControlPacketV5PublishCodec.encodeFromContext"))
        assertTrue(s.contains("wireSizeFromContext"))
    }

    // ---------------------------------------------------------------------
    // Other canonical codecs (MessagePackFormatByteCodec, TlsRecordCodec)
    // ---------------------------------------------------------------------

    @Test
    fun `MessagePackFormatByte emits range + point arms over rawByte`() {
        val s = emit(EmitterFixtures.messagePackFormatByte(), "MessagePackFormatByte")
        assertTrue(s.contains("public object MessagePackFormatByteCodec : Codec<MessagePackFormatByte>"))
        assertTrue(s.contains("rawByte in 0..127"))
        assertTrue(s.contains("type == 192"))
    }

    @Test
    fun `TlsRecord emits dispatch over content-type and routes payload variants`() {
        val s = emit(EmitterFixtures.tlsRecord(), "TlsRecord")
        assertTrue(s.contains("public object TlsRecordCodec : Codec<TlsRecord>"))
        assertTrue(s.contains("TlsContentTypeCodec.decode(buffer, context)"))
        assertTrue(s.contains("TlsRecordHandshakeCodec.decodeFromContext"))
        assertTrue(s.contains("TlsRecordApplicationDataCodec.decodeFromContext"))
        assertTrue(s.contains("TlsRecordChangeCipherSpecCodec.decode"))
        assertTrue(s.contains("TlsRecordAlertCodec.decode"))
    }

    // ---------------------------------------------------------------------
    // Cross-cutting: no banned patterns anywhere
    // ---------------------------------------------------------------------

    @Test
    fun `every canonical codec lacks the @file Suppress ktlint blanket`() {
        val all =
            listOf(
                EmitterFixtures.mqttFixedHeader() to "MqttFixedHeader",
                EmitterFixtures.grpcFrame() to "GrpcFrame",
                EmitterFixtures.mqttConnectFlags() to "MqttConnectFlags",
                EmitterFixtures.mqttPubAck() to "MqttPubAck",
                EmitterFixtures.pingResponse() to "PingResponse",
                EmitterFixtures.riffChunk() to "RiffChunk",
                EmitterFixtures.wsFrame() to "WsFrame",
                EmitterFixtures.wsFrameHeader() to "WsFrameHeader",
                EmitterFixtures.controlPacketV5() to "ControlPacketV5",
                EmitterFixtures.messagePackFormatByte() to "MessagePackFormatByte",
                EmitterFixtures.tlsRecord() to "TlsRecord",
            )
        for ((plan, simple) in all) {
            val s = emit(plan, simple)
            assertFalse(s.contains("@file:Suppress(\"ktlint\")"), "$simple emitted the banned ktlint blanket suppression")
        }
    }
}
