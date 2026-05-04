# Phase I.1 — Pluggable length encoding via `@UseCodec`

This is the design spec for the first slice of Phase I (post-Stage-H
work). Read this file first; the prior session's discussion is in
the conversation transcript but the conclusions are captured here.

The goal: replace the MQTT-specific `@RemainingLength` annotation
with a generic mechanism where any user-supplied codec can drive
length encoding and (optionally) buffer-bounding behavior.
Consequence: the processor and `:buffer-codec` runtime contain
**zero** protocol-specific code; MQTT, LEB128, MIDI VLQ, ASN.1 BER,
WebSocket extended-length, and any future variable-length encoding
are all expressed as user codecs of the same shape.

## Problem statement

`@RemainingLength` ships in `:buffer-codec` today. It hard-codes
MQTT v3.1.1 §2.2.3's specific wire shape: 7 data bits + continuation
bit per byte, LSB-first, max 4 bytes, AND a side-effect of setting
`buffer.limit()` to `position + value`. Real-world variable-length
integer encodings differ on byte order, max bytes, sentinel
strategy, signedness (zig-zag vs straight), and bounding semantics
— see the survey table in the conversation. No single annotation
can cleanly cover them.

The deferred-decisions row in `PHASE_9_RESET.md` previously sketched
"decompose `@RemainingLength` into `@VarByteInt(maxBytes = N)` +
`@BoundsRemaining`." That was rejected in this session: `@VarByteInt`
parameterizes only one of the variation axes (max bytes); the others
(byte order, zig-zag, sentinel form) would either bloat the
annotation parameter set into a leaky abstraction or stay
MQTT-shaped. The cleaner path is to make the *codec* the unit of
pluggability, not the annotation.

## Design

### Pluggability via existing `@UseCodec`

`@UseCodec(SomeCodec::class)` already exists (slice 10a). Today it
attaches only to `Payload`-typed fields. Phase I.1 lifts it to
scalar/length fields:

```kotlin
@UseCodec(MqttRemainingLengthCodec::class)
val remainingLength: UInt
```

The processor validates that the target's `Codec<T>` matches the
field type. The user supplies the read/write logic in 30–35 lines
of hand-written codec code. The processor knows nothing about
var-byte ints.

This unblocks the same pattern for every other length encoding:

```kotlin
@UseCodec(LEB128UnsignedCodec::class)            val n: UInt        // Protobuf, WASM
@UseCodec(MidiVlqCodec::class)                   val deltaTime: UInt // MIDI MSB-first VLQ
@UseCodec(BerLengthCodec::class)                 val length: Int    // ASN.1 short/long form
@UseCodec(WebSocketLengthCodec::class)           val payloadLen: ULong // sentinel-extended
```

`@LengthPrefixed @UseCodec(SomeCodec::class)` (slice 10a's deferred
composition) lands as the prefix-codec form for variable-prefix
lists like the MQTT v5 property list.

### `BoundingLengthCodec<T>` sub-interface

The codec opts into "my decoded value should narrow `buffer.limit()`":

```kotlin
package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

interface BoundingLengthCodec<T : Any> : Codec<T> {
    fun applyBound(buffer: ReadBuffer, decodedValue: T)
}
```

The processor inspects the `@UseCodec` target's KSClassDeclaration
supertypes. If `BoundingLengthCodec` is present, the emitter wraps
the post-decode region in:

```kotlin
val outerLimit = buffer.limit()
val value = lengthCodec.decode(buffer, ctx)
lengthCodec.applyBound(buffer, value)
try {
    // ... decode bounded fields ...
} finally {
    buffer.setLimit(outerLimit)
}
```

This is **the same try/finally pattern slice 10f already emits for
`@RemainingLength`**, just driven by interface detection instead of
annotation hard-coding. Codecs that don't bound the buffer (a plain
length read whose value is consumed by a sibling `@LengthFrom`)
implement `Codec<T>` directly and skip the sub-interface.

### Peek = run decode on a peek view

Codec authors implement **only** `decode` (plus `encode`,
`wireSize`, optionally `applyBound`). No separate `peekLength`
method.

The framework's peek walker, when it encounters a `@UseCodec` field,
materializes a non-consuming view of the stream from the current
peek offset, runs `codec.decode` against the view, and reads the
buffer position delta to know the byte width consumed. If decode
throws "insufficient bytes," peek returns `NeedsMoreData`.

```kotlin
val peekView = stream.peekBuffer(currentOffset, peekBudget)
    ?: return PeekResult.NeedsMoreData
val priorPos = peekView.position()
val value = try {
    lengthCodec.decode(peekView, DecodeContext.Empty)
} catch (_: BufferUnderflowException) {
    return PeekResult.NeedsMoreData
}
val width = peekView.position() - priorPos
// ...advance offset by width, add value to frame size, etc.
```

Same code path drives both decode and peek — zero risk of the two
diverging via separate implementations.

### Per-field-type peek budget

The peek view's `maxBytes` parameter is computed **at emit time**
from the field's `KSType`, not from a per-codec const. Heuristic:
`max(⌈typeBits / 7⌉, 1 + typeBytes)`, covering both 7-bit-continuation
encodings and sentinel-extended encodings:

| Field type   | Peek-view budget |
|--------------|------------------|
| `Byte`/`UByte`     | 2  |
| `Short`/`UShort`   | 3  |
| `Int`/`UInt`       | 5  |
| `Long`/`ULong`     | 10 |

Covers MQTT (UInt → 5, codec uses 1–4), LEB128 (UInt → 5, codec uses
1–5), WebSocket sentinel-extended length (ULong → 10, codec uses
1+8 = 9). Adversarial encodings beyond these heuristics can revisit
with a per-codec opt-in override; **none of the current target
protocols need one**.

### `StreamProcessor.peekBuffer(offset, maxBytes): ReadBuffer?`

New method on `:buffer/.../stream/BufferStream.kt`'s `StreamProcessor`
interface. Returns a non-consuming view starting at `offset`, capped
at `maxBytes` bytes (or `null` if fewer bytes than the smaller of
`maxBytes` and what's actually available are vendable). Implementation
uses chunk-spanning copy when needed; framing fields are short so the
copy is cheap.

This is the only new `:buffer` API. Existing `peekByte` / `peekShort` /
`peekInt` / `peekLong` stay — they remain the fast path for fixed-width
peek.

### `MqttRemainingLengthCodec` reference fixture

Lives at `:buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttRemainingLengthCodec.kt`.
The full sketch (subject to small refinement during implementation):

```kotlin
package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

object MqttRemainingLengthCodec : BoundingLengthCodec<UInt> {
    private const val MAX_VALUE: UInt = 0x0FFF_FFFFu

    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt {
        var value = 0u
        var multiplier = 1u
        repeat(4) {
            val encoded = buffer.readUnsignedByte().toUInt()
            value += (encoded and 0x7Fu) * multiplier
            if ((encoded and 0x80u) == 0u) return value
            multiplier *= 128u
        }
        throw DecodeException(
            fieldPath = "MqttRemainingLength",
            bufferPosition = buffer.position(),
            expected = "continuation bit clear within 4 bytes",
            actual = "5th continuation byte (malformed per MQTT v3.1.1 §2.2.3)",
        )
    }

    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
        require(value <= MAX_VALUE) {
            "MQTT remaining length must be <= $MAX_VALUE; got $value"
        }
        var remaining = value
        do {
            var encodedByte = remaining and 0x7Fu
            remaining = remaining shr 7
            if (remaining > 0u) encodedByte = encodedByte or 0x80u
            buffer.writeByte(encodedByte.toByte())
        } while (remaining > 0u)
    }

    override fun wireSize(value: UInt, context: EncodeContext): WireSize =
        WireSize.Exact(
            when {
                value < 128u -> 1
                value < 16_384u -> 2
                value < 2_097_152u -> 3
                else -> 4
            },
        )

    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
```

Lives with the MQTT fixtures because it's MQTT-specific. The mqtt
repo cutover will eventually copy or depend on this. There is no
`:buffer-codec-stdlib` of "common length codecs" — that's premature
bundling.

### What `@RemainingLength` becomes

**Deleted.** No backward-compat shim, no sugar that desugars to
`@UseCodec(MqttRemainingLengthCodec::class)`. The annotation, its
processor support, validator, FieldSpec member, emit branches, and
all referencing tests are removed in step 10 of the landing order.

Migration: every `@RemainingLength val x: UInt` in fixtures becomes
`@UseCodec(MqttRemainingLengthCodec::class) val x: UInt`. Mechanical
find-replace across ~5 fixture files.

The deferred-decisions row in `PHASE_9_RESET.md` is resolved by
deletion, not by decomposition.

## Landing order

Each step is individually green; nothing breaks until step 10's
deletion. The 204 `:buffer-codec-test:jvmTest` tests + 4
`:buffer-flow:CodecConnectionSmokeTest` tests are the regression
baseline — they must stay green throughout.

1. **Add `StreamProcessor.peekBuffer(offset: Int, maxBytes: Int): ReadBuffer?`**
   to `:buffer/.../stream/BufferStream.kt` (interface +
   `DefaultStreamProcessor` impl). Returns a non-consuming view via
   chunk-spanning copy when needed; `null` if fewer than
   `min(maxBytes, available - offset)` bytes are vendable.
2. **Add `BoundingLengthCodec<T>` interface** to
   `:buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/`.
   ~5 lines plus kdoc.
3. **Lift `@UseCodec` validator for scalar fields** in the processor.
   Today the validator's `validateUseCodec` rejects non-`Payload`
   fields. Relax to allow scalar/length types when `@RemainingBytes`
   isn't also present. Diagnostic ergonomics: clear messages when
   field type doesn't match codec's `Codec<T>` parameter.
4. **Implement processor detection of `BoundingLengthCodec` impls**
   + emit the `applyBound` + try/finally bounding pattern. Symmetric
   with slice 10f's existing emit for `@RemainingLength`; just
   driven by interface inspection instead of annotation matching.
5. **Implement processor's per-field-type peek-budget computation**
   in the emitter. Lookup table from the field `KSType` to a literal
   integer; the literal lands as a constant in the generated peek
   code.
6. **Implement generic `@UseCodec` peek walker** — materialize peek
   view via `stream.peekBuffer(...)`, run `codec.decode`, measure
   position delta, advance walker offset. Substitute for the
   existing slice 8 `@RemainingLength` peek branch.
7. **Add `MqttRemainingLengthCodec` fixture** in
   `:buffer-codec-test/.../mqtt/`. Self-contained; no test changes
   yet.
8. **Migrate one variant — `MqttPacket.PingReq`** — as the smoke
   test. Confirm: byte-exact wire output identical to current emit;
   `peekFrameSizeForPingReqCompletesAtTwoBytes` and other PingReq
   peek tests stay green; encode/decode round-trip stays green. If
   any test breaks, the bug is in steps 1–6 — fix before continuing.
9. **Migrate remaining variants** — `MqttPacket.{Connect, Publish,
   PingResp, Disconnect}` plus `MqttSubAck`, `MqttConnect`. Each
   migration is a 1-line annotation swap. After this step, no
   fixture references `@RemainingLength`.
10. **Delete `@RemainingLength`** — the annotation declaration, the
    `FieldSpec.RemainingLength` sealed member, `analyzeRemainingLength`,
    `appendDecodeRemainingLength`, `appendEncodeRemainingLength`, the
    peek branch, the validator, and `:buffer-codec-processor:test`
    cases referencing it. The slice 10c `shouldEmitPartial` carve-out
    that gates on `@RemainingLength` lifts to gate on
    `BoundingLengthCodec`-via-`@UseCodec` instead.
11. **Lift `@LengthPrefixed @UseCodec` composition** (slice 10a's
    deferred row). Enables the MQTT v5 property-list shape:
    `@LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val
    properties: List<MqttProperty>`. Drives the next phase's J.M
    work cleanly; not a Phase I.1 blocker but ergonomically belongs
    in the same slice.

## Decisions explicitly NOT made

- **Ergonomic helper for `remainingLength = 0u` defaults on
  `PingReq`/`PingResp`/`Disconnect`.** Current explicit `= 0u` is
  fine. Lifting to "emitter computes RL = 0 implicitly when body is
  empty" is deferred polish; not a Phase I.1 blocker.
- **`MAX_WIRE_BYTES` static contract on codecs.** Rejected: the
  per-field-type peek budget heuristic covers all current target
  protocols without per-codec opt-in.
- **A `:buffer-codec-stdlib` module of common length codecs.**
  Rejected as premature bundling. Each consumer authors the codec
  it needs in its own source tree (or copies from
  `:buffer-codec-test` fixtures).
- **Backward-compat sugar for `@RemainingLength`.** Rejected — two
  ways to express the same shape muddies the contract; one-line
  migration cost is trivial.
- **Phase I.2 (sentinel-extended length annotation for WebSocket).**
  Folded into Phase I.1 — `WebSocketLengthCodec` is just another
  user codec implementing `BoundingLengthCodec<ULong>`.
- **Compile-time validation that the codec's `decode` is the inverse
  of its `encode`.** Out of scope. Round-trip tests in fixtures
  catch this.
- **Phase I.3 (numeric-keyed conditional fields)** for
  PUBACK/PUBREC/PUBREL/PUBCOMP/DISCONNECT v5's optional reason+props
  on `RL > 2`. Stays as a separate phase.

## Risks and watchpoints

1. **Generated peek correctness depends on user-codec correctness.**
   A buggy codec that consumes the wrong number of bytes desyncs the
   peek offset for every subsequent field. Mitigation: ship the
   `MqttRemainingLengthCodec` fixture as a documented reference;
   round-trip tests + drip-fed peek tests are the main correctness
   net.
2. **`StreamProcessor.peekBuffer` materialization cost.** Materializing
   a chunk-spanning view requires a copy when data crosses chunk
   boundaries. Acceptable for framing fields (always short, near the
   start of a frame). If a future codec needs to peek deep into a
   frame body, revisit.
3. **`BufferUnderflowException` may not be the actual class** the
   buffer module throws on insufficient reads. Confirm at step 1
   when implementing `peekBuffer`; the emit pattern in step 6 adapts
   to whatever the actual exception type is.
4. **Slice 10c `shouldEmitPartial` carve-out lift in step 10.** Today
   it gates on `@RemainingLength`; new gate is "any field whose
   `@UseCodec` target implements `BoundingLengthCodec`." The
   semantic is identical; only the detection mechanism changes.
   Watch for any subtle ordering bug in the `analyzeField` walk.
5. **Validator diagnostics for scalar `@UseCodec`.** Slice 10a's
   diagnostics were tuned for `Payload`-typed fields. When step 3
   relaxes the validator, the messages need to make sense for
   scalar field violations too. Diagnostic regression tests in
   `:buffer-codec-processor:test` are the net.

## Out of scope for Phase I.1

- Modeling the rest of MQTT v5 (Phase J.M).
- Modeling WebSocket frames (Phase J.W).
- Repo cutovers for `mqtt` and `websocket` (Phase K).
- Phase I.3, I.4, I.5, I.6 (other emitter capability gaps and
  composition verifications).

These all stack on Phase I.1 once it lands.

## Reference

- `STAGE_H_RESUME.md` — Stage H final state, including slice 10g
  (`:buffer-flow` smoke test closing acceptance #4).
- `PHASE_9_RESET.md` — Stages A–H execution plan, locked decisions,
  deferred-decisions table.
- `PHASE_10_DESIGN_NOTES.md` — derivation history for the locked
  decisions.
- Slice 10a `@UseCodec` doctrine in `STAGE_H_RESUME.md` §"Slice 10a
  + 10b shape — landed" — the validator + emitter shape Phase I.1
  extends.
- Slice 10f outer-limit capture pattern in `STAGE_H_RESUME.md`
  §"Slice 10f shape — landed" — the try/finally template Phase I.1
  reuses for `BoundingLengthCodec`.
