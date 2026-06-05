# Custom frame-size peek — consumer-supplied framing override

**Status:** design (for review) · **Track:** `codec/varint-h3` · **Date:** 2026-06-05

## Problem

Some protocols carry a frame size in a shape the processor cannot derive
declaratively. The motivating case is **RFC 6455 WebSocket** (`websocket/WsFrame.kt`):

```text
byte1 | byte2 | [ext-len 16 if ind==126 | ext-len 64 if ind==127] | [mask 4 if MASK] | payload
```

The payload length is **escape-coded** across header fields —
`payloadLen = extLen64 ?: extLen16 ?: (byte2 & 0x7F)` — and a 4-byte masking key
folds **between** the length and the payload. There is no single length field a
`BoundingLengthCodec`/`@LengthFrom` can point at, and the 126/127 escape is not a
shape the generated peek walker models. So `WsFrameCodec.peekFrameSize` falls back
to `NoFraming`, and the websocket repo hand-writes the peek in
`WebSocketCodec.readNextFrame` (`WsFrame.kt:93-99`).

Decode/encode are **already fine** — the header fields decode via `@When` grammar-1
predicates. The gap is **only** the generated `peekFrameSize`.

## Non-goal

Teaching the IR the 126/127 escape table. That over-fits the processor to one
protocol. The escape is protocol-specific; it belongs with the protocol's model.

## Mechanism — companion object implements the existing `FrameDetector`

No new interface needed. `buffer-codec` already defines (`Codec.kt:106`):

```kotlin
interface FrameDetector {
    fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.NoFraming
}
// Codec<T> : Encoder<T>, Decoder<T>, FrameDetector  — generated codecs already are FrameDetectors.
```

The consumer declares a companion object on the `@ProtocolMessage` type that
implements `FrameDetector`. When present, the generated codec's `peekFrameSize`
delegates to it instead of the derived walker / `NoFraming` fallback. The
companion owns ONLY framing (the total byte count) — field decode/encode stay
generated.

**Load-bearing contract** (the same one generated peeks satisfy by construction;
here the consumer preserves it by hand):

```
peekFrameSize(stream, base) is Complete  ⇒  Complete.bytes == (bytes decode consumes)
```

for any fully-buffered frame, and `NeedsMoreData` for every shorter prefix. A
single drip-feed test locks it (see WsFrameCodecTest).

The consumer adds a companion to their existing model — no annotation, the logic
lives next to the wire layout it mirrors:

```kotlin
@DispatchOn(FrameHeaderByte1::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface WsFrame<out P : Payload> {
    // ... variants unchanged ...

    companion object : FrameDetector {
        override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
            if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
            val byte2 = stream.peekByte(baseOffset + 1).toInt() and 0xFF
            val masked = (byte2 and 0x80) != 0
            val indicator = byte2 and 0x7F
            var offset = 2
            val payloadLen: Long = when (indicator) {
                126 -> {
                    if (stream.available() - baseOffset < offset + 2) return PeekResult.NeedsMoreData
                    val v = ((stream.peekByte(baseOffset + offset).toInt() and 0xFF) shl 8) or
                        (stream.peekByte(baseOffset + offset + 1).toInt() and 0xFF)
                    offset += 2; v.toLong()
                }
                127 -> {
                    if (stream.available() - baseOffset < offset + 8) return PeekResult.NeedsMoreData
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or (stream.peekByte(baseOffset + offset + i).toLong() and 0xFF)
                    offset += 8; v
                }
                else -> indicator.toLong()
            }
            if (masked) offset += 4
            val total = offset + payloadLen.toInt()
            return if (stream.available() - baseOffset >= total) PeekResult.Complete(total)
                   else PeekResult.NeedsMoreData
        }
    }
}
```

## What the generated codec emits

When the analyzer sees the `@ProtocolMessage` type (or sealed parent) declares a
companion implementing `FrameSizePeek`, the emitter replaces the derived
`peekFrameSize` body with a single delegation:

```kotlin
// WsFrameCodec<P> (the dispatcher codec)
override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
    WsFrame.peekFrameSize(stream, baseOffset)
```

This wins over the derived walker (and over the `NoFraming` fallback) whenever the
companion is present. It applies uniformly to:
- a plain message codec (companion on the data class), and
- a sealed dispatcher codec (companion on the sealed parent) — the WS case, where
  framing is opcode-independent so the override sits at the parent.

## Processor changes (scope)

1. **buffer-codec:** add `FrameSizePeek` (≈ the snippet above).
2. **Analyzer (`ProtocolMessageProcessor`):** resolve whether the message type's
   companion object implements `FrameSizePeek`; record `customPeek: ClassName?`
   (the companion's reference) on `CodecShape`.
3. **Emitter (`CodecEmitter`):** at the top of `buildPeekFrameFun` **and**
   `buildDispatchPeekFun`, if `customPeek != null` emit the one-line delegation and
   return — skipping the derived walker entirely. Everything else (decode, encode,
   `Partial`, dispatch routing) is untouched.

No change to decode/encode codegen. No new annotation. Existing goldens stay
byte-identical (no type gains a `FrameSizePeek` companion except the WS fixture).

## Contract & risk

The one risk a hand-written peek introduces is **drift**: the consumer's size math
could disagree with what the generated decode consumes. Mitigations:
- The interface KDoc states the `peek.bytes == decode consumption` invariant.
- The WS fixture test drip-feeds one byte at a time (`NeedsMoreData` until the last
  byte → `Complete(n)`), then asserts `n == decodeBuffer.position()` — the exact
  invariant, locked for masked/unmasked × 7-bit/16/64-bit length. Any drift fails
  loudly. This is the same assertion the HTTP/3 and (former) bounding tests use.

The override covers **only** sizing; field decode stays generated, so drift is
confined to "is the byte count right," which one test catches — not to field
layout, types, or order.

## Alternatives considered

- **`@ProtocolMessage(framing = WsFraming::class)`** (annotation pointing at a
  consumer object). Equivalent power; anticipated in `WsFrame.kt:195`. The companion
  approach is preferred: no annotation, the framing lives on the type it frames, and
  it can't be attached to the wrong type. (If a type genuinely can't host a
  companion, the annotation remains a clean fallback — both can coexist, companion
  taking precedence.)
- **Declarative escape-length IR** (model 126/127 in the walker). Rejected:
  over-fits the processor to RFC 6455; the next protocol's escape table differs.
- **Hand-write the entire `Codec`** (no codegen). Rejected: throws away the
  generated decode/encode/`Partial`/dispatch the fixture exists to exercise, just to
  customize sizing.

## Open questions for review

1. **Interface name** — `FrameSizePeek` vs `FramePeek` vs `CustomFraming`.
2. **Companion vs annotation as the primary surface** (design assumes companion).
3. Should the override be allowed on a **variant** (per-opcode framing), or only on
   the message/sealed-parent? WS only needs parent-level; restricting to
   message/parent keeps it simple. Variant-level can be added later if a protocol
   needs per-type framing.
