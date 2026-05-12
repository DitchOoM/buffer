package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Opaque, platform-typed handle for bytes that need to outlive the wire
 * frame they were decoded from — IPC forwarding, persistence, debug
 * capture, default-decode for payloads whose codec isn't pinned at decode
 * time.
 *
 * Shape: the canonical Pattern #2 from the buffer-codec lockdown plan
 * (consumer-owned [PlatformBuffer] allocated via `factory.allocate(...) +
 * write(source)`). The handle's actual stores a [PlatformBuffer] internally
 * on every platform; the `expect class` boundary is what shields KSP's
 * transitive Payload-shape walk from descending into the internal buffer
 * field. Walker sees the property typed as `OpaqueBytesHandle`, finds the
 * type is not forbidden, not [Payload], not a value class — and stops.
 *
 * `OpaqueBytesHandle` is intentionally not a [Payload] itself — protocol-
 * specific concerns (mqtt's `PublishPayload`, websocket's binary message
 * type, etc.) supply their own thin wrapper that does the Payload marker
 * duty. The companion codec [OpaqueBytesHandleCodec] implements the
 * canonical decode/encode flow that those wrappers' codecs delegate to.
 */
expect class OpaqueBytesHandle

/**
 * Construct from a consumer-owned [PlatformBuffer]. The platform actual
 * takes ownership of [bytes] and is responsible for its lifetime.
 *
 * Named `opaqueBytesFrom` rather than `OpaqueBytesHandle` to avoid a
 * constructor / top-level-fun ambiguity in the actuals.
 */
expect fun opaqueBytesFrom(bytes: PlatformBuffer): OpaqueBytesHandle

/**
 * Returns a [ReadBuffer] view over the bytes storage, position reset to 0.
 * The returned view aliases the handle's internal buffer — do not free.
 */
expect fun OpaqueBytesHandle.asReadBuffer(): ReadBuffer

/** Number of bytes carried by this handle. */
expect fun OpaqueBytesHandle.byteSize(): Int

/** Content-equality across two handles. */
expect fun OpaqueBytesHandle.handleEquals(other: OpaqueBytesHandle): Boolean

/** Content hash. */
expect fun OpaqueBytesHandle.handleHashCode(): Int

/**
 * Canonical [Codec] for [OpaqueBytesHandle]. Implements buffer-v1 Pattern #2:
 * decode allocates a consumer-owned [PlatformBuffer] via the factory bound
 * to [BufferFactoryKey] (falling back to [BufferFactory.Default] when
 * absent), copies the remaining wire bytes into it, and wraps the result in
 * an [OpaqueBytesHandle]. Encode writes the handle's bytes into the target
 * buffer; [wireSize] is [WireSize.Exact] of the carried byte count.
 *
 * Use as the default codec on a missing-codec path (e.g., MqttCodec's
 * fallback when no per-topic publish codec is registered). For codecs
 * targeting a specific typed Payload (Pattern #1), prefer a direct
 * `Codec<MyTypedPayload>` rather than routing through this handle.
 */
object OpaqueBytesHandleCodec : Codec<OpaqueBytesHandle> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): OpaqueBytesHandle {
        val factory = context[BufferFactoryKey] ?: BufferFactory.Default
        val remaining = buffer.remaining()
        val dst = factory.allocate(remaining)
        dst.write(buffer)
        dst.resetForRead()
        return opaqueBytesFrom(dst)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: OpaqueBytesHandle,
        context: EncodeContext,
    ) {
        buffer.write(value.asReadBuffer())
    }

    override fun wireSize(
        value: OpaqueBytesHandle,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.byteSize())
}
