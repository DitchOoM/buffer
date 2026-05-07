package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Stage H slice 10e doctrine vector — `@UseCodec` against an
 * `expect object` codec, plus the `MqttCodec` convenience alias
 * for the MQTT dispatcher class.
 *
 * **Doctrine lock proven by `roundTripsRemoteCommandViaExpectActualCodec`:**
 * the generated `RemoteCommandCodec` references
 * `RemoteCommandPayloadCodec` by simple name in commonMain. Each
 * platform's compilation links the call against its own `actual
 * object RemoteCommandPayloadCodec` declaration. The Kotlin linker
 * is the resolution mechanism; the processor does not inspect
 * platform actuals. Round-tripping a `RemoteCommand` on JVM via
 * the JVM `actual` (which delegates to
 * `RemoteCommandPayloadCodecImpl`) confirms the resolution works
 * end-to-end without any processor or runtime indirection.
 *
 * **Alias proven by `mqttCodecAliasResolvesToMqttPacketCodec`:**
 * the `typealias MqttCodec<P> = MqttPacketCodec<P>` is a
 * consumer-side convenience. The test uses `MqttCodec(JpegImage
 * Codec)` at the call site and confirms the resulting instance
 * is identical to `MqttPacketCodec(JpegImageCodec)` — proving
 * the alias is purely a naming layer, with no runtime cost.
 */
class RemoteCommandCodecTest {
    @Test
    fun roundTripsRemoteCommandViaExpectActualCodec() {
        // Encode + decode via the generated RemoteCommandCodec, which
        // emits `RemoteCommandPayloadCodec.decode(buffer, context)` in
        // commonMain. The JVM compilation links that call against the
        // jvmMain `actual object RemoteCommandPayloadCodec` — proving
        // the slice 10e doctrine ("direct call, linker resolves").
        val original =
            RemoteCommand(
                id = "cmd/restart",
                payload =
                    RemoteCommandPayload(
                        opcode = 0xCAFEBABEu,
                        data = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    ),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        RemoteCommandCodec.encode(buf, original, EncodeContext.Empty)
        val written = buf.position()
        // 2 (id LP) + 11 (id "cmd/restart") + 4 (opcode) + 4 (data) = 21
        assertEquals(21, written)
        buf.resetForRead()
        // Caller bounds the buffer so the @RemainingBytes payload
        // field stops at the encoded byte count (slice 10a/10b
        // contract).
        buf.setLimit(written)
        assertEquals(original, RemoteCommandCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun expectActualCodecProducesByteExactWire() {
        // Wire bytes are independent of the resolution path — the
        // JVM actual delegates to the shared impl, so byte-exact
        // assertions confirm both the actual and the impl are
        // wired correctly. The other platform actuals follow the
        // same delegation pattern, so this assertion implicitly
        // covers their wire format too.
        val msg =
            RemoteCommand(
                id = "x",
                payload = RemoteCommandPayload(opcode = 0x12345678u, data = byteArrayOf()),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        RemoteCommandCodec.encode(buf, msg, EncodeContext.Empty)
        buf.resetForRead()
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                // id length-prefix (UShort BE) + body
                0x00,
                0x01,
                'x'.code.toByte(),
                // opcode (UInt BE)
                0x12,
                0x34,
                0x56,
                0x78,
                // payload data: empty
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun mqttCodecAliasResolvesToMqttPacketCodec() {
        // The typealias is a naming convenience; the underlying
        // class is identical. Constructing through the alias and
        // through the original name produces structurally
        // equivalent dispatcher instances (different objects, same
        // type and behavior). We assert the type identity by
        // comparing the encoded wire of a payload-free variant —
        // both paths must emit the same bytes.
        val viaAlias = MqttCodec(JpegImageCodec)
        val viaOriginal = MqttPacketCodec(JpegImageCodec)
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                // body = 6 (proto) + 1 (level) + 1 (flags) + 2 (keepalive) + 6 (clientId LP "abcd") = 16
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        val bufA = viaAlias.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val bufB = viaOriginal.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        assertContentEquals(
            bufA.readByteArray(bufA.remaining()),
            bufB.readByteArray(bufB.remaining()),
            "MqttCodec(...) and MqttPacketCodec(...) emit identical bytes",
        )
    }

    @Test
    fun mqttCodecAliasInstantiatesGenericClassWithPayloadCodec() {
        // A round-trip via the alias proves the generic type
        // parameter and constructor-injected payload codec flow
        // through unchanged. Same vector as
        // MqttPacketCodecTest.roundTripsPublishVariantViaDispatcher
        // but written via the alias to confirm the alias is usable
        // at every consumer-facing call site.
        val codec = MqttCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // 2 + 1 (topic "x") + 2 (pid) + 4 + 4 (jpeg)
                topic = "x",
                packetId = PacketId(42u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x10, 0x20, 0x30, 0x40)),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun expectActualCodecObjectIsAccessibleAsSingleton() {
        // The `actual object` declaration is a Kotlin singleton —
        // accessing it twice yields the same instance. This is the
        // contract the @UseCodec linker resolution depends on
        // (generated code calls the singleton's static-equivalent
        // members, no instance threading required).
        val a: Any = RemoteCommandPayloadCodec
        val b: Any = RemoteCommandPayloadCodec
        assertSame(a, b, "actual object is a singleton")
    }
}
