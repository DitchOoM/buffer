# `@ForwardCompatible` — skip-and-preserve unknown sealed variants

> Design spec / PR handoff. Implement against `buffer-codec-processor`. Self-contained:
> a fresh session should be able to build the feature from this document alone.

## Motivation

Generated sealed-dispatch decoders **throw** `DecodeException` when they hit a
discriminator they don't recognize. Forward-compatible, length-delimited protocols
need the opposite: an old decoder must **skip** an op it doesn't know (advance past
its framed payload) and **preserve** it, so newer ops survive round-trips through
older clients (relay) and on-disk frames (persistence).

This is a generic engine capability — nothing protocol-specific belongs in
`buffer-codec`. The driving consumer is an external terminal protocol (`§5` frames:
`Frame{seq,ts,kind,base,ops}`, each op `opcode(1) + varint(len) + payload`), but the
feature must not know that.

## Scope

Two protocol-agnostic features are needed for that consumer; **this spec covers
only the first.** The second is tracked at the end.

1. **`@ForwardCompatible`** — skip + preserve unknown variants. *(this doc)*
2. **`@Count`** — element-count-prefixed lists. *(separate PR — see "Tracked separately")*

Everything protocol-specific stays consumer-side, authored against existing
extension points (no buffer changes):
- framing prefix → a `BoundingLengthCodec<UInt>` (e.g. a full-width LEB128 length
  codec) wired via `@FramedBy` — exactly how `MqttRemainingLengthCodec` is used,
  and like it, **lives in the consumer module, not `:buffer-codec`** (the design
  doc rejects a codec stdlib; each consumer authors the codec it needs).
- per-field varints → `@UseCodec(...)`.
- discriminator → `@PacketType` / `@DispatchOn`.

## Current behavior to change

In `buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt`
(line numbers approximate — verify):

- `buildDispatcherDecodeFun` (~7068) — simple `@PacketType` dispatch. Emits
  `else -> throw DecodeException(...)`.
- `buildDispatchOnDecodeFun` (~7709) — `@DispatchOn` dispatch. Same `else -> throw`.
- `buildDispatcherPeekFrameFun` (~7173) / `buildDispatchOnPeekFun` (~7849) — peek.
- Encode dispatch (`when (value)` over variants) and the wireSize dispatch — need a
  new arm for the unknown variant.

Annotation definitions live in
`buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/annotations/Annotations.kt`.

`@FramedBy` (~523) + `BoundingLengthCodec` (`buffer-codec/.../BoundingLengthCodec.kt`)
already provide the length-bounded decode/encode and the peek-without-resolving-variant
machinery we build on (`FramedEncoder`, `applyBound`, `maxWireSize`).

## New API (add to `buffer-codec` Annotations.kt)

```kotlin
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class ForwardCompatible(val unknown: KClass<*>)

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class UnknownVariant

// Caller-controlled allocator for preserved (un-deserializable) bytes.
val ForwardCompatibleFactoryKey = DecodeContext.Key<BufferFactory>("forwardCompatibleFactory")
```

## The unknown variant (consumer-declared)

```kotlin
@ProtocolMessage
@FramedBy(<FramingCodec>::class)
@ForwardCompatible(unknown = Op.Unknown::class)
sealed interface Op {
    @ProtocolMessage @PacketType(0x12)
    data class Scroll(/* @UseCodec varints */) : Op

    @UnknownVariant
    data class Unknown(val opcode: Int, val raw: PlatformBuffer) : Op   // raw = opaque payload
}
```

Decisions (settled):
- **`data class`, opcode stored** (not a value class). `opcode` is **required**, not
  cosmetic: `raw` is the opaque *payload only* (no opcode byte to derive from), and
  re-encode must write a discriminator that, for an unknown op, is unknowable at
  compile time — it can only come from this field.
- `raw` is the **payload** (the framed body, excluding opcode and length prefix).
- `raw` is a **`PlatformBuffer`**, never `ByteArray` — preserved bytes are destined
  for the wire (relay/persist), which is a buffer consumer, and generated code must
  never fabricate a `ByteArray`.
- Content equality is automatic: data-class equals over `opcode: Int` +
  `raw: PlatformBuffer` (buffers override `equals`/`hashCode` content-wise via
  `bufferEquals`/`bufferHashCode`, `ReadBuffer.kt:966`).

## Generated code

`<FramingCodec>` = the codec named in this type's `@FramedBy`. `<DISC>` = the
discriminator wire byte (for simple dispatch, the read `discriminator`).

### decode — replace the `else -> throw`

```kotlin
else -> {
    buffer.position(opcodeStart + 1)                 // past the 1-byte discriminator
    val len = <FramingCodec>.decode(buffer, context)
    val frameEnd = buffer.position() + len.toInt()   // position is now at payload start

    val factory = context[ForwardCompatibleFactoryKey] ?: BufferFactory.managed()
    val raw = factory.allocate(len.toInt())
    val savedLimit = buffer.limit()
    buffer.setLimit(frameEnd)
    raw.write(buffer)                                 // copies payload; advances buffer to frameEnd
    buffer.setLimit(savedLimit)                       // position now exactly past the op
    raw.resetForRead()
    Op.Unknown(discriminator, raw)
}
```

`opcodeStart` = the position captured before reading the discriminator (the existing
generated code already captures `discriminatorPosition` — reuse it). For `@DispatchOn`,
the discriminator may be wider than 1 byte; advance by the discriminator width, and
take `opcode`/`discriminator` from the already-decoded dispatch value.

### encode — new arm in the `when (value)` dispatch

Re-emit the discriminator from the stored field, then frame the payload with the same
`@FramedBy` codec (the path known variants already use via `FramedEncoder`):

```kotlin
is Op.Unknown -> {
    buffer.writeUByte(value.opcode.toUByte())              // discriminator from stored field
    <FramingCodec>.encode(buffer, value.raw.remaining().toUInt(), context)
    buffer.write(value.raw.slice())                        // slice() = non-consuming, re-entrant
}
```

### wireSize — new arm

```kotlin
is Op.Unknown -> {
    val p = value.raw.remaining()
    WireSize.Exact(1 + (<FramingCodec>.wireSize(p.toUInt(), context) as WireSize.Exact).bytes + p)
}
```

### peekFrameSize — no change

The existing `@FramedBy` peek derives the total frame size from the prefix **without
resolving the variant**, so an unknown op already peeks correctly. Confirm with a test;
write no new peek code.

## Copy / lifetime contract

- **One copy, at decode — mandatory.** The op is opaque: there is no typed form to
  restructure into, so retaining raw bytes *is* the decode, and they outlive the
  (often pooled) frame buffer. `slice()` is therefore forbidden here — its contract
  (`ReadBuffer.kt:83`) says a slice must not outlive the parent's scope. Use
  `factory.allocate + write`.
- **Zero copies at encode.** We own `raw`; `slice()` is verified non-consuming
  (does not move the source position) and zero-copy, and the slice is transient
  within the encode call — safe and re-entrant.
- **Default `managed()`**: GC lifetime, no manual free. A caller wanting native/pooled
  memory injects a pool-backed `BufferFactory` via `ForwardCompatibleFactoryKey` and
  owns freeing.

## Byte-identity

Required (consumer persists/relays frames). Holds because `Unknown` re-encode reuses
the same `@FramedBy` framing codec that **known** variants use — both re-derive the
length prefix rather than preserving original length bytes, so they share one
byte-identity guarantee (canonical framing-codec encoding). The discriminator is a
single byte, so no encoding ambiguity.

## Compile-time rules (enforce in the processor)

1. `@ForwardCompatible` **requires** `@FramedBy` on the same type → else error:
   *"cannot skip an unknown variant without a framing length."* You cannot skip what
   you cannot measure.
2. `unknown` must name a member of the sealed type marked `@UnknownVariant`, of shape
   `(opcode: Int, raw: PlatformBuffer)` (also accept `(Int, ReadBuffer)`).
3. `@UnknownVariant` must **not** carry `@PacketType` (it is the `else` sink).
4. Exactly **one** `@UnknownVariant` per `@ForwardCompatible` union.
5. Generated preserve-paths must allocate through `ForwardCompatibleFactoryKey`
   (default `managed()`) — never `ByteArray`.

## Test obligations

- Round-trip **byte-identical** for a frame containing an unknown op (decode → encode).
- Unknown op flanked by known ops in one frame; correct ordering and offsets.
- `peekFrameSize` correct on an unknown op.
- Relay identity through both `managed()` and a pooled factory (via context key).
- Wrapper transparency (`PooledBuffer` / `TrackedSlice`) per `WrapperTransparencyTests`.
- Negative: `@ForwardCompatible` without `@FramedBy` fails compilation with rule (1)'s
  message; two `@UnknownVariant`s fail with rule (4).

## Tracked separately — `@Count` (element-count lists)

The driving consumer's lists (`ops`, `runs`, `cells`, `lines`) use an **element-count**
prefix (`varint(N) + N elements`). buffer-codec currently supports only **byte-length**
lists (`LengthPrefixedUseCodecList`/`RemainingBytes`/`LengthFrom` — all drain-to-limit;
the framing codec receives a *byte* count, decode loops until the bound drains; no
`@Count`, no `repeat(n)`). Byte-identity for that consumer therefore needs a generic
`@Count(codec)`: encode writes `list.size` via the codec, decode does `repeat(n)`.
Same factory/copy rules apply if it ever retains bytes. Independent of this PR.
