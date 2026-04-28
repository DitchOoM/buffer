package com.ditchoom.buffer.codec.processor.emitter

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke harness used during initial Phase 7 development to print every
 * canonical emitter output to stdout so the snapshots could be pinned by
 * inspection. Kept in-tree as a quick sanity check; each test asserts the
 * emitted text is non-empty and lacks the banned `@file:Suppress("ktlint")`
 * blanket and the banned `var _size = 0; _size += 1; return _size` accumulator.
 */
class SmokeEmitterTest {
    private val registry = EmitterFixtures.standardRegistry()
    private val emitter = CodecEmitter(registry)

    private fun emitText(
        plan: com.ditchoom.buffer.codec.processor.ir.Plan,
        simple: String,
    ): String {
        val cn = EmitterFixtures.cn(simple)
        return emitter.emit(plan, cn).toString()
    }

    @Test
    fun `MqttFixedHeader emits non-empty source`() {
        val text = emitText(EmitterFixtures.mqttFixedHeader(), "MqttFixedHeader")
        assertTrue(text.contains("public object MqttFixedHeaderCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
        assertTrue(!text.contains("var _size = 0"))
    }

    @Test
    fun `RiffChunk emits non-empty source`() {
        val text = emitText(EmitterFixtures.riffChunk(), "RiffChunk")
        assertTrue(text.contains("public object RiffChunkCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `ControlPacketV5 emits non-empty source`() {
        val text = emitText(EmitterFixtures.controlPacketV5(), "ControlPacketV5")
        assertTrue(text.contains("public object ControlPacketV5Codec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `WsFrame emits non-empty source`() {
        val text = emitText(EmitterFixtures.wsFrame(), "WsFrame")
        assertTrue(text.contains("public object WsFrameCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `MessagePackFormatByte emits non-empty source`() {
        val text = emitText(EmitterFixtures.messagePackFormatByte(), "MessagePackFormatByte")
        assertTrue(text.contains("public object MessagePackFormatByteCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `TlsRecord emits non-empty source`() {
        val text = emitText(EmitterFixtures.tlsRecord(), "TlsRecord")
        assertTrue(text.contains("public object TlsRecordCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `PingResponse emits non-empty source`() {
        val text = emitText(EmitterFixtures.pingResponse(), "PingResponse")
        assertTrue(text.contains("public object PingResponseCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `GrpcFrame emits non-empty source`() {
        val text = emitText(EmitterFixtures.grpcFrame(), "GrpcFrame")
        assertTrue(text.contains("public object GrpcFrameCodec"))
        assertTrue(!text.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `WsFrameHeader emits non-empty source`() {
        val text = emitText(EmitterFixtures.wsFrameHeader(), "WsFrameHeader")
        assertTrue(text.contains("public object WsFrameHeaderCodec"))
    }

    @Test
    fun `MqttFixedHeader has no _size accumulator`() {
        val text = emitText(EmitterFixtures.mqttFixedHeader(), "MqttFixedHeader")
        // Per banned-patterns: const-fold a fixed-width single-byte wireSize.
        assertTrue(!text.contains("var _size = 0; _size += 1; return _size"))
        assertTrue(!text.contains("var size = 0\n    size += 1"))
    }
}
