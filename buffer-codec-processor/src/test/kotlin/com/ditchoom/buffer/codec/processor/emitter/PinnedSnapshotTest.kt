package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 7 hard-bar tests: every canonical fixture's emitted [com.squareup.kotlinpoet.FileSpec]
 * must match a pinned snapshot byte-for-byte (after CRLF/LF normalisation).
 *
 * Snapshot drift triggers a failure that surfaces as a unified diff on the
 * `assertEquals` value-compare; the developer reviewing the PR sees the
 * before/after side-by-side.
 *
 * Coverage:
 *  - Shape 1 (Plan.Leaf fixed-width primary ctor): MqttFixedHeaderCodec
 *  - Shape 2 (Plan.Leaf fixed prefix + tail buffer slice): GrpcFrameCodec
 *  - Shape 5 (Plan.Object_ singleton): PingResponseCodec
 *  - Shape 1 again, sealed-discriminator codec: WsFrameHeaderCodec
 *
 * The canonical 8 codec list also includes ControlPacketV5Codec, WsFrameCodec,
 * RiffChunkCodec, MessagePackFormatByteCodec, TlsRecordCodec — these are
 * exercised by [StructuralEmitterTest] which asserts shape-level invariants
 * structurally rather than against a pinned text. The four pinned snapshots
 * here cover the simplest, most stable shapes; the structural tests cover
 * shapes whose text would be sensitive to emitter improvements (variant
 * ordering, conditional emission of `ctx`, etc.).
 */
class PinnedSnapshotTest {
    private val emitter = CodecEmitter(EmitterFixtures.standardRegistry())

    private fun emitText(
        plan: Plan,
        classType: ClassName,
    ): String = emitter.emit(plan, classType).toString()

    @Test
    fun `MqttFixedHeader emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.mqttFixedHeader(), EmitterFixtures.cn("MqttFixedHeader"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.MqttFixedHeader),
            CapturedSnapshots.normalise(emitted),
        )
    }

    @Test
    fun `PingResponse emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.pingResponse(), EmitterFixtures.cn("PingResponse"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.PingResponse),
            CapturedSnapshots.normalise(emitted),
        )
    }

    @Test
    fun `GrpcFrame emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.grpcFrame(), EmitterFixtures.cn("GrpcFrame"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.GrpcFrame),
            CapturedSnapshots.normalise(emitted),
        )
    }

    @Test
    fun `WsFrameHeader emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.wsFrameHeader(), EmitterFixtures.cn("WsFrameHeader"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.WsFrameHeader),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /** Slice 2 hard-bar: `StringField` + `VarInt` strategies emit the pinned text. */
    @Test
    fun `StringHeader emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.stringHeader(), EmitterFixtures.cn("StringHeader"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.StringHeader),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /**
     * Slice 3 hard-bar: `Collection_` strategy with `LengthSource.Inline.Varint`
     * (MQTT v5 properties shape) emits the pinned text.
     */
    @Test
    fun `MqttPropertyShape emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.mqttPropertyShape(), EmitterFixtures.cn("MqttPropertyShape"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.MqttPropertyShape),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /** Slice 4 hard-bar: `Plan.Sealed_` Unframed (RIFF chunk) emits the pinned text. */
    @Test
    fun `RiffChunkSlice4 emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.riffChunkSlice4(), EmitterFixtures.cn("RiffChunkSlice4"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.RiffChunkSlice4),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /** Slice 4 hard-bar: `Plan.Sealed_` BodyLength (MQTT FixedHeader) emits the pinned text. */
    @Test
    fun `ControlPacketV5Slice4 emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.controlPacketV5Slice4(), EmitterFixtures.cn("ControlPacketV5Slice4"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.ControlPacketV5Slice4),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /** Slice 4 hard-bar: `Plan.Sealed_` PeekOnly (WsFrame-shape) emits the pinned text. */
    @Test
    fun `WsFrameSlice4 emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.wsFrameSlice4(), EmitterFixtures.cn("WsFrameSlice4"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.WsFrameSlice4),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /**
     * Slice 5a hard-bar: `Plan.Sealed_` BodyLength with a `VariantPlan.WithPayload`
     * variant — pinned dispatcher routes to `*FromContext` overloads with `is X<*>`
     * star projection.
     */
    @Test
    fun `ControlPacketV5Slice5a emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.controlPacketV5Slice5a(), EmitterFixtures.cn("ControlPacketV5Slice5a"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.ControlPacketV5Slice5a),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /**
     * Slice 5a hard-bar: `Plan.Leaf` with a variable-size SPI field — pinned
     * `wireSize` substitutes `descriptor.wireSizeRaw` for `fixedSize == -1`.
     */
    @Test
    fun `VariableSizeSpiLeaf emits the pinned snapshot`() {
        val emitted = emitText(EmitterFixtures.variableSizeSpiLeaf(), EmitterFixtures.cn("VariableSizeSpiLeaf"))
        assertEquals(
            CapturedSnapshots.normalise(CapturedSnapshots.VariableSizeSpiLeaf),
            CapturedSnapshots.normalise(emitted),
        )
    }

    /**
     * Slice 5.5 hard-bar — Item C: `Plan.Leaf` with an asymmetric SPI descriptor
     * (different `decodeRaw` and `encodeRaw` strings). Asserts decode emits the
     * decode expression and encode emits the encode expression — never swapped.
     * Mirrors legacy `CustomFieldDescriptor` with separate read/write FunctionRefs.
     */
    @Test
    fun `Asymmetric SPI descriptor emits decodeRaw on decode and encodeRaw on encode`() {
        val emitted = emitText(EmitterFixtures.asymmetricSpiLeaf(), EmitterFixtures.cn("AsymmetricSpiLeaf"))
        // Decode side reads the decodeRaw expression.
        assert(emitted.contains("val cidr = buffer.readCidr()")) {
            "Asymmetric SPI: decode side should call buffer.readCidr() — got:\n$emitted"
        }
        // Encode side calls the encodeRaw expression.
        assert(emitted.contains("buffer.writeCidr(value.cidr)")) {
            "Asymmetric SPI: encode side should call buffer.writeCidr(value.cidr) — got:\n$emitted"
        }
        // The decode expression must not appear on the encode side and vice-versa.
        // The encode body is the block following `fun encode(... ) {`.
        val encodeBody = emitted.substringAfter("fun encode(").substringAfter("{").substringBefore("\n  override")
        assert(!encodeBody.contains("buffer.readCidr()")) {
            "Asymmetric SPI: encode body must not contain decodeRaw expression — got:\n$encodeBody"
        }
        val decodeBody = emitted.substringAfter("fun decode(").substringAfter("{").substringBefore("\n  override")
        assert(!decodeBody.contains("buffer.writeCidr")) {
            "Asymmetric SPI: decode body must not contain encodeRaw expression — got:\n$decodeBody"
        }
    }

    /** Banned-pattern: per the rearchitecture plan, fixed-width wireSize must
     * not emit `var _size = 0; _size += 1; return _size`. */
    @Test
    fun `MqttFixedHeader does not emit the banned _size accumulator`() {
        val emitted = emitText(EmitterFixtures.mqttFixedHeader(), EmitterFixtures.cn("MqttFixedHeader"))
        assert(!emitted.contains("var _size = 0; _size += 1; return _size")) {
            "MqttFixedHeader emitted the banned _size accumulator:\n$emitted"
        }
    }

    /** Banned-pattern: no blanket `@file:Suppress("ktlint")`. */
    @Test
    fun `no canonical codec emits the banned ktlint blanket suppression`() {
        val canonical =
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
        for ((plan, simple) in canonical) {
            val emitted = emitText(plan, EmitterFixtures.cn(simple))
            assert(!emitted.contains("@file:Suppress(\"ktlint\")")) {
                "$simple emitted the banned `@file:Suppress(\"ktlint\")` blanket"
            }
        }
    }
}
