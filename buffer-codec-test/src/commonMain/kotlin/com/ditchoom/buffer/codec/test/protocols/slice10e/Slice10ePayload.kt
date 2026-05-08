package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * Doctrine vector — `@UseCodec` against an
 * `expect object` codec declaration.
 *
 * Locked decision: the cross-platform resolution path for
 * `@UseCodec(Foo::class)` is **direct call, linker resolves**. KSP
 * sees the `expect object` in commonMain and emits `Foo.decode(...)`
 * by simple name; the Kotlin linker on each platform resolves the
 * symbol to that platform's `actual object` declaration. The
 * processor doesn't inspect actuals — it doesn't need to. This
 * matches the Kotlin Multiplatform expect/actual contract: the
 * compiler guarantees an actual exists at link time, and the
 * symbol's signature is fixed by the expect declaration.
 *
 * Why this is the right doctrine:
 *   - **No KSP burden.** KSP processes commonMain metadata; it has
 *     no view of platform actuals. Forcing the processor to verify
 *     each actual exists would require parallel KSP runs per
 * platform (defeats the doctrine of "single KSP run on
 *     commonMain metadata, generated codecs in commonMain").
 *   - **Symmetric with hand-written multiplatform code.** Kotlin
 *     consumers writing `Foo.decode(...)` in commonMain rely on the
 *     same linker-resolution semantics; generated code matches.
 *   - **No runtime indirection.** `Foo.decode(buffer, context)` is
 *     a direct static dispatch on each platform. No reflection, no
 *     SPI lookup, no module ServiceLoader. Zero runtime cost.
 *
 * The vector here is a `RemoteCommand` data class with a typed
 * `payload: RemoteCommandPayload` field annotated `@UseCodec(
 * RemoteCommandPayloadCodec::class)`. `RemoteCommandPayloadCodec`
 * is `expect object` in commonMain with per-platform actuals
 * (`jvmMain`, `jsMain`, `wasmJsMain`, `nativeMain`); each actual
 * delegates to the shared `internal RemoteCommandPayloadCodecImpl`
 * so the wire format is identical across platforms but the
 * resolution path is exercised end-to-end.
 *
 * Wire layout:
 *
 * ```text
 *   +--------+--------+--------+--------+
 *   | id length (UShort BE) | id UTF-8 |
 *   +--------+--------+--------+--------+
 *   | payload bytes (variable, decoded by RemoteCommandPayloadCodec) |
 *   +--------+--------+--------+--------+
 * ```
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class RemoteCommand(
    @LengthPrefixed val id: String,
    @RemainingBytes
    @UseCodec(RemoteCommandPayloadCodec::class)
    val payload: RemoteCommandPayload,
)

/**
 * Payload — a 4-byte opcode followed by an arbitrary
 * binary blob. Implements `Payload` so the §8 raw-bytes ban carves
 * it out (the marker is the documented escape hatch for "the
 * consumer takes responsibility for the bytes it holds").
 */
data class RemoteCommandPayload(
    val opcode: UInt,
    val data: ByteArray,
) : Payload {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteCommandPayload) return false
        return opcode == other.opcode && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = opcode.hashCode() * 31 + data.contentHashCode()
}

/**
 * Shared codec implementation. Each platform's `actual object
 * RemoteCommandPayloadCodec` delegates here so the wire format is
 * a single source of truth — only the linker-resolution path
 * differs across platforms.
 *
 * `internal` is the right visibility: the impl is an implementation
 * detail of the actuals, not a consumer-facing API. The actuals are
 * what consumers reference (and what `@UseCodec` annotations point
 * to via the expect declaration).
 */
internal object RemoteCommandPayloadCodecImpl : Codec<RemoteCommandPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): RemoteCommandPayload {
        val opcode = buffer.readUInt()
        val data = buffer.readByteArray(buffer.remaining())
        return RemoteCommandPayload(opcode, data)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: RemoteCommandPayload,
        context: EncodeContext,
    ) {
        buffer.writeUInt(value.opcode)
        buffer.writeBytes(value.data)
    }

    override fun wireSize(
        value: RemoteCommandPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4 + value.data.size)
}

/**
 * `expect` codec declaration. Each platform supplies an `actual
 * object RemoteCommandPayloadCodec : Codec<RemoteCommandPayload>`
 * in its source set; the actuals delegate to
 * `RemoteCommandPayloadCodecImpl` so wire bytes are identical
 * across platforms.
 *
 * Generated code (`RemoteCommandCodec`) emits
 * `RemoteCommandPayloadCodec.decode(...)` in commonMain. The
 * Kotlin linker resolves the symbol per platform — proving the
 * "direct call, linker resolves" doctrine end-to-end without any
 * processor-side awareness of platform actuals.
 */
expect object RemoteCommandPayloadCodec : Codec<RemoteCommandPayload>
