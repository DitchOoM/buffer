package com.ditchoom.buffer.codec

/**
 * A [Codec] whose [decode] returns a **borrowed view** into the source
 * buffer instead of a self-contained value — the explicit opt-in that
 * lets a `@RemainingBytes @UseCodec(C) val: T` field carry a non-[Payload]
 * type (including `ReadBuffer` itself).
 *
 * The buffer-codec lockdown forbids raw buffer types in `@ProtocolMessage`
 * fields by default because they leak ownership ambiguity (who frees,
 * when, aliased?). Implementing `ViewCodec` *is* the ownership answer, and
 * it is a contract the implementor must honor and document:
 *
 *  - **decode** returns a view whose lifetime is tied to the buffer it was
 *    decoded from (e.g. `buffer.readBytes(buffer.remaining())`). The
 *    consumer must read, copy, or otherwise consume the view before the
 *    source buffer is recycled — the contract zero-copy streaming
 *    protocols (HTTP/3 DATA/HEADERS payloads, QPACK field sections)
 *    already place on their frame readers. Nothing is allocated and
 *    nothing needs freeing beyond the source buffer's own lifecycle.
 *  - **encode** must be non-consuming (restore the view's position after
 *    copying) so a value can be size-checked and re-encoded.
 *
 * When the decoded value must *outlive* the source buffer, this is the
 * wrong tool — use a [Payload]-marked type over an [OwnedBytesHandle]
 * (consumer-owned copy) instead.
 */
public interface ViewCodec<T> : Codec<T>
