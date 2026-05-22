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
 * field. Walker sees the property typed as `OwnedBytesHandle`, finds the
 * type is not forbidden, not [Payload], not a value class — and stops.
 *
 * `OwnedBytesHandle` is intentionally not a [Payload] itself — protocol-
 * specific concerns (mqtt's `PublishPayload`, websocket's binary message
 * type, etc.) supply their own thin wrapper that does the Payload marker
 * duty. The companion codec [OwnedBytesHandleCodec] implements the
 * canonical decode/encode flow that those wrappers' codecs delegate to.
 *
 * **Equality**: every platform actual overrides `equals` / `hashCode` to
 * delegate to [handleEquals] / [handleHashCode] (byte-content comparison).
 * Two handles with the same bytes are `==`, so `data class` containers
 * that carry an `OwnedBytesHandle` field get structural equality "for
 * free" without each declaring custom `equals` themselves.
 */
expect class OwnedBytesHandle

/**
 * Construct from a consumer-owned [PlatformBuffer]. The platform actual
 * takes ownership of [bytes] and is responsible for its lifetime.
 *
 * Named `ownedBytesFrom` rather than `OwnedBytesHandle` to avoid a
 * constructor / top-level-fun ambiguity in the actuals.
 */
expect fun ownedBytesFrom(bytes: PlatformBuffer): OwnedBytesHandle

/**
 * Returns a [ReadBuffer] view over the bytes storage, position reset to 0.
 * The returned view aliases the handle's internal buffer — do not free.
 */
expect fun OwnedBytesHandle.asReadBuffer(): ReadBuffer

/** Number of bytes carried by this handle. */
expect fun OwnedBytesHandle.byteSize(): Int

/** Content-equality across two handles. */
expect fun OwnedBytesHandle.handleEquals(other: OwnedBytesHandle): Boolean

/** Content hash. */
expect fun OwnedBytesHandle.handleHashCode(): Int

/**
 * Platform-safe fallback [BufferFactory] for [OwnedBytesHandleCodec] when
 * the [DecodeContext] does not carry a [BufferFactoryKey].
 *
 * [BufferFactory.Default] is GC- / ARC- / cleaner-managed on every supported
 * platform — JVM `DirectByteBuffer` via cleaner, JS / WasmJs GC, Apple
 * `NSData` ARC, Linux native heap (Default routes to `managedBufferFactory`
 * since the V2 migration; the malloc/free `NativeBuffer` is now opt-in via
 * `PlatformBuffer.allocateNative` or `deterministic()`). So this fallback
 * is simply `BufferFactory.Default` on every target.
 *
 * Production consumers that care about the allocation strategy supply
 * their own factory via `DecodeContext.with(BufferFactoryKey, myFactory)`
 * — this fallback only fires when no key is bound.
 */
fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.Default

/**
 * Canonical [Codec] for [OwnedBytesHandle]. Implements buffer-v1 Pattern #2:
 * decode allocates a consumer-owned [PlatformBuffer] via the factory bound
 * to [BufferFactoryKey] (falling back to [ownedBytesFallbackFactory] —
 * [BufferFactory.Default] on every platform), copies the remaining wire
 * bytes into it, and wraps the result in an [OwnedBytesHandle]. Encode
 * writes the handle's bytes into the target buffer; [wireSize] is
 * [WireSize.Exact] of the carried byte count.
 *
 * Use as the default codec on a missing-codec path (e.g., MqttCodec's
 * fallback when no per-topic publish codec is registered). For codecs
 * targeting a specific typed Payload (Pattern #1), prefer a direct
 * `Codec<MyTypedPayload>` rather than routing through this handle.
 */
object OwnedBytesHandleCodec : Codec<OwnedBytesHandle> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): OwnedBytesHandle {
        val factory = context[BufferFactoryKey] ?: ownedBytesFallbackFactory()
        val remaining = buffer.remaining()
        val dst = factory.allocate(remaining)
        dst.write(buffer)
        dst.resetForRead()
        return ownedBytesFrom(dst)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: OwnedBytesHandle,
        context: EncodeContext,
    ) {
        buffer.write(value.asReadBuffer())
    }

    override fun wireSize(
        value: OwnedBytesHandle,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.byteSize())
}
