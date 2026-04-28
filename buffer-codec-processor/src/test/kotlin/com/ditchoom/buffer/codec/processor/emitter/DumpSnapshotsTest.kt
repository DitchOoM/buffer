package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test

/**
 * Diagnostic-only test that prints every canonical fixture's emitted source.
 * Used during snapshot pinning; left in-tree as a quick way to inspect the
 * emitter's output without re-running the full snapshot suite.
 *
 * Disabled by default (no `@Test` body assertions) — set the env var
 * `BUFFER_CODEC_DUMP_SNAPSHOTS=1` to print.
 */
class DumpSnapshotsTest {
    private val emitter = CodecEmitter(EmitterFixtures.standardRegistry())

    @Test
    fun dumpAll() {
        if (System.getenv("BUFFER_CODEC_DUMP_SNAPSHOTS") != "1") return
        listOf<Pair<String, Plan>>(
            "MqttFixedHeader" to EmitterFixtures.mqttFixedHeader(),
            "GrpcFrame" to EmitterFixtures.grpcFrame(),
            "MqttConnectFlags" to EmitterFixtures.mqttConnectFlags(),
            "MqttPubAck" to EmitterFixtures.mqttPubAck(),
            "PingResponse" to EmitterFixtures.pingResponse(),
            "RiffChunk" to EmitterFixtures.riffChunk(),
            "WsFrame" to EmitterFixtures.wsFrame(),
            "WsFrameHeader" to EmitterFixtures.wsFrameHeader(),
            "ControlPacketV5" to EmitterFixtures.controlPacketV5(),
            "MessagePackFormatByte" to EmitterFixtures.messagePackFormatByte(),
            "TlsRecord" to EmitterFixtures.tlsRecord(),
            "MqttPropertyShape" to EmitterFixtures.mqttPropertyShape(),
            "RiffChunkSlice4" to EmitterFixtures.riffChunkSlice4(),
            "ControlPacketV5Slice4" to EmitterFixtures.controlPacketV5Slice4(),
            "WsFrameSlice4" to EmitterFixtures.wsFrameSlice4(),
        ).forEach { (simple, plan) ->
            val cn = EmitterFixtures.cn(simple)
            val text = emitter.emit(plan, cn).toString()
            println("===== $simple =====\n$text\n===== end =====")
        }
    }
}
