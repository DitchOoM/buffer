# Stage H Resume Briefing

This document briefs the next session on the work landed on
`feature/directional-codec` and the design surface Stage H opens.
Read `PHASE_9_RESET.md` first — Stage H's spec lives in §"Stage H —
Payload SAM via MQTT v5 PUBLISH" of that document. This file is the
incremental delta and the open design questions.

## Branch state

- Branch: `feature/directional-codec`, head `1a013d3`. Not pushed.
- 14 stacked commits since the last `main`-merged commit (`d83a534`).
  All commits are individually buildable and individually green.
- Test counts at head:
  - `:buffer-codec-test:jvmTest` — 166 tests, all pass.
  - `:buffer-codec-processor:test` — 46 tests, all pass.
  - `:buffer-codec-test:jsNodeTest` — 93 tests, 1 pre-existing failure
    (`WavFmtChunkCodecTest.decodeFailsWhenChunkSizeUnderBoundsTheBody`,
    JS-vs-JVM divergence in `setLimit + readString` behavior, predates
    Stage E work, unrelated).

## Capability landed since the last main merge

In commit order — see each commit's full message for the design notes:

| Commit | Slice | Capability |
|--------|-------|------------|
| `2c333fa` | E.1 | Doctrine lock for `@LengthFrom`/`@WhenTrue`/field-path attribution (rows 18–20) |
| `f18d857` | E.2 | `@WhenTrue` against sibling Boolean (`Boolean` as 1-byte scalar) |
| `11e6ba5` | E.3 | Dotted `@WhenTrue("sibling.property")` + value-class fields |
| `02aef48` | E.3.5 | `@WhenTrue` inner widened to `@LengthPrefixed val: String?` |
| `6b3f029` | E.4 | `@LengthFrom("siblingField") val: String` (non-adjacent) |
| `9af5edc` | E.5a | Sequential peek walk + non-terminal `@LengthPrefixed val: String` |
| `d3064af` | E.5b | Non-terminal `@WhenTrue` + full MQTT v3 CONNECT vector |
| `48185a1` | F.6 | `@DispatchOn` value-class discriminator dispatcher |
| `f28a836` | F.6.5 | UInt discriminator (HTTP/2 frame header) |
| `dcca9dd` | — | HTTP/2 R-bit enforcement via `Http2StreamId` value class |
| `1556373` | G.7a | `@LengthFrom` on `List<@ProtocolMessage>` (HTTP/2 SETTINGS) |
| `031e0d6` | G.7b | `@RemainingBytes` on `List<scalar>` (MQTT SUBACK body) |
| `6834858` | G.8 | `@RemainingLength` var-int field (full MQTT SUBACK) |
| `898f438` | — | Doc: `@RemainingLength` decompose-before-merge followup |
| `c8bdb89` | — | MQTT CONNECT promoted to full real-spec packet |
| `5375a9f` | — | MqttPacket variants promoted to spec-compliant framing |
| `ac47a7c` | — | MqttPacket: PINGREQ / PINGRESP variants |
| `f262db0` | G.9 | `@LengthFrom` dotted form via `LengthSource` sealed (Http2Frame.Settings) |
| `1a013d3` | — | Value-class field wireOrder propagation through sequential peek |

### Annotation surface as it stands today

- `@ProtocolMessage(wireOrder, direction)` — message-level marker.
- `@PacketType(value, wire)` — sealed dispatch variant discriminator.
- `@DispatchOn(type)` — bit-packed dispatch parent annotation.
- `@DispatchValue` — property-level marker on a `@DispatchOn` discriminator.
- `@LengthPrefixed(prefix)` — prefix-then-body framing for `String` and
  `@ProtocolMessage` data class fields.
- `@LengthFrom(field)` — sibling-byte-count framing; field accepts
  simple `"sibling"` or dotted `"sibling.property"`.
- `@WhenTrue(expression)` — predicate-gated optional field; expression
  accepts simple `"sibling"` or dotted `"sibling.property"`.
- `@WireBytes(value)` — narrow scalar wire width.
- `@WireOrder(order)` — per-field byte order override.
- `@RemainingBytes` — read-until-buffer-end carrier for `String` and
  `List<scalar>` shapes.
- `@RemainingLength` — MQTT v3.1.1 §2.2.3 var-int that reads/writes
  the length AND sets buffer.limit to bound subsequent decode.

### FieldSpec hierarchy as it stands today

- `FieldSpec.FixedSize` (sealed sub-interface — implements `wireBytes: Int`)
  - `Scalar` — natural-width or `@WireBytes`-narrowed scalar
  - `ValueClassScalar` — `@JvmInline value class` with single-scalar
    inner. Carries `valueClassWireOrder` (slice 9 follow-on) for
    correct peek-side byte assembly when the value class declares
    `@ProtocolMessage(wireOrder = Endianness.Little)`.
- `FieldSpec.LengthPrefixedString` — `@LengthPrefixed val: String`
- `FieldSpec.LengthPrefixedMessage` — `@LengthPrefixed val: ProtocolMessage`
- `FieldSpec.LengthFromString` / `FieldSpec.LengthFromList` — both
  carry a typed `source: LengthSource` (sealed `Sibling | ValueClassProperty`).
- `FieldSpec.RemainingBytesScalarList` — `@RemainingBytes val: List<S>`
- `FieldSpec.RemainingLength` — MQTT var-int with implicit bounding
- `FieldSpec.Conditional` — `@WhenTrue` slot; carries `condition: ConditionRef`
  (sealed `Sibling | ValueClassProperty`) and `inner: ConditionalInner`
  (sealed `Scalar | LengthPrefixedString`)

### Architecture conventions worth knowing before touching the emitter

1. **Emit silence, validator diagnostics.** Out-of-shape inputs return
   `null` from `analyzeField` (no codec emitted). The validator in
   `ProtocolMessageProcessor` is what surfaces user-facing diagnostics.
   This split is consistent across every slice.
2. **`LengthSource` exhaustive `when`.** Every emit site that branches
   on `LengthSource` must handle both `Sibling` and `ValueClassProperty`.
   The type system enforces this — no nullable fields representing form
   distinction. Same pattern for `ConditionRef` and `ConditionalInner`.
3. **Sequential peek walk** (`appendSequentialPeek`) is the general
   path. The two short-circuits (`@RemainingLength` upfront,
   all-FixedSize tighter check) live in `buildPeekFrameFun` before the
   walk. New variable-length field types should slot into the walk's
   `when (field)` branch.
4. **Trust contract for user-supplied length values** (row 16). The
   codec doesn't cross-check `value.<sibling>` against actual body
   byte counts at encode time — measuring would allocate. The user
   keeps them consistent. This is documented in every `@LengthFrom`
   field's encode kdoc.
5. **Try/finally for `setLimit`.** Anywhere decode bounds the buffer
   (`@LengthFromList`, `@RemainingLength`), the post-bound region
   runs inside `try { … } finally { setLimit(outerLimit) }` so the
   caller's outer limit is restored even on decode failure.
6. **Generated code is self-contained.** No runtime helpers in the
   `:buffer-codec` module beyond what protocols universally need
   (`Codec`, `DecodeException`, `EncodeException`, `WireSize`,
   `PeekResult`). Every codec inlines its own var-int loops, byte
   assembly, etc.

## Outstanding pre-merge followup

One row remains in `PHASE_9_RESET.md`'s deferred-decisions table that
should land before the PR merges:

- **Decompose `@RemainingLength` into `@VarByteInt(maxBytes = N)` +
  `@BoundsRemaining`.** `@RemainingLength` today is MQTT-v3-§2.2.3-
  shaped on three axes: byte format (capped at 4 bytes vs LEB128's
  unbounded), implicit-bounding behavior (setLimit-on-decode is
  MQTT-style), and name (matches MQTT spec terminology). Works for
  MQTT v3 + v5 and incidentally for MIDI VLQ-style framing; doesn't
  cover Protobuf, WebAssembly, or Avro varints. The followup
  decomposes into orthogonal annotations so non-MQTT varint
  protocols can use `@VarByteInt` alone. **Decision deferred until
  a second var-int-using vector lands so the `@VarByteInt`
  parameter set can be designed against a concrete second case.**

The other followup (value-class field wireOrder propagation) was
resolved in `1a013d3`.

## Stage H — open design questions

`PHASE_9_RESET.md` §Stage H specifies the vector and capability list
but not the implementation shape. The prior implementation lives in
`.claude/worktrees/agent-acbc960a6159a0113/` (49 reverted commits)
and predates the architectural refactors landed since
(`LengthSource`, `FieldSpec.FixedSize`, the sequential peek walk,
the wireOrder propagation). The prior worktree is **reference, not
specification** — re-derive the design against today's emitter shape.

### Capabilities required (from PHASE_9_RESET §Stage H)

1. Generic-bounded payload slots — `<P : Payload>` parameterizing
   the parent message so `MqttPublishCodec<P>` is generic in the
   payload type.
2. `PayloadDecoder<P>` SAM with `Partial` receiver — partial-decode
   pattern that decodes packet headers + properties but defers
   payload bytes to the user's `PayloadDecoder<P>` lambda.
3. `PayloadEncoder<P>` SAM — symmetric for encode.
4. Aggregator dispatcher with throwing-default lambdas — one lambda
   per payload-bearing variant of `MqttControlPacket`, called when
   an unexpected packet type arrives.
5. `@UseCodec` annotation + `expect`/`actual` resolution — KSP
   emits direct calls to `expect` codec objects; the linker picks
   the platform `actual`.
6. `MqttCodec` convenience alias — top-level wrapper.

### Phasing recommendation

Stage H is the biggest single piece in the plan. Prior session
flagged three sequencings; the recommendation that survived
discussion was **phased**:

- **Slice 10a — bare-Payload escape hatch + `@UseCodec`.** Smallest
  vector that exercises the typed-payload path: a `@LengthFrom`-
  bounded `Payload`-typed field whose codec is named via
  `@UseCodec(SomeCodec::class)`. No generics, no Partial, no
  aggregator. ~150 lines. Real-spec MQTT v3 PUBLISH (typed payload,
  no QoS-conditional packetId — simpler than v5) is a candidate
  vector. Deliverable: a `data class MqttPublishV3(...,
  @LengthFrom("remainingLength") @UseCodec(JpegImageCodec::class)
  val payload: JpegImage)` round-trips byte-exact, where
  `JpegImage : Payload` and `JpegImageCodec : Codec<JpegImage>`
  is user-supplied.
- **Slice 10b — generic-bounded payload slots.** Lift the slice 10a
  shape to `MqttPublishV3<P : Payload>`. Generic codec emission
  (`object MqttPublishV3Codec<P : Payload>(payloadCodec:
  Codec<P>) : Codec<MqttPublishV3<P>>`). New emit territory — we
  don't currently emit generic codecs. Acceptance: typed-payload
  PUBLISH round-trips byte-exact (PHASE_9_RESET acceptance #1).
- **Slice 10c — `Partial` decode pattern.** Emit
  `MqttPublishCodec.Partial` returning headers + a
  `PayloadDecoder<P>` callback signature. The user invokes the
  callback with their `PayloadDecoder` to complete decode.
  Required for the `:buffer-flow` smoke test (PHASE_9_RESET
  acceptance #4) — without `Partial`, the flow layer would have
  to know the payload type at type-erased boundaries.
- **Slice 10d — aggregator with throwing-default lambdas.** Emit
  `MqttControlPacketCodec.decode` taking one lambda per payload-
  bearing variant; missing handler throws with field-path
  attribution. Acceptance #3.
- **Slice 10e — `@UseCodec` `expect`/`actual` lock + `MqttCodec`
  alias.** Lock the resolution decision (the deferred-decisions
  row "@UseCodec expect/actual resolution path" already leans
  toward "direct call, linker resolves"). Add the convenience
  alias.

### Architectural questions to resolve before slice 10a

- **Does `@UseCodec` accept `Decoder<T>` / `Encoder<T>` / `Codec<T>`?**
  The codec interface trinity (slice 0 / Stage A) supports
  decode-only and encode-only. `@UseCodec` should accept whichever
  matches the emit path (a `@ProtocolMessage(direction = DecodeOnly)`
  message that uses a typed payload via `@UseCodec` only needs the
  decoder side).
- **Where does the `@UseCodec` codec instance come from at emit
  time?** Two options: (a) the annotation references a `KClass<*>`
  pointing to an `object` declaration; KSP emits a direct `<class>.decode(...)` call, linker resolves expect/actual. (b) the
  annotation references a property path; the codec instance is
  threaded through `CodecContext`. Option (a) is simpler and matches
  the prior worktree's apparent direction.
- **Does the bare-`Payload` field need its own annotation to
  distinguish "bytes go through `@UseCodec`" vs "bytes are passed
  raw"?** The §8 raw-bytes ban already carves out `Payload`-typed
  fields. The minimal slice 10a shape is "if a field's type extends
  `Payload`, the codec emits via the field's `@UseCodec` codec
  reference." Without `@UseCodec`, no codec can be emitted for a
  `Payload`-typed field, so the validator should reject the
  `Payload`-typed field without `@UseCodec` (or pair with another
  resolution mechanism).
- **What does the validator say when `@UseCodec` is on a non-`Payload`
  field?** Per CLAUDE.md it composes with `@LengthPrefixed`,
  `@RemainingBytes`, `@LengthFrom`. So `@UseCodec` is general-
  purpose, not Payload-specific. Slice 10a's narrow shape is
  `@LengthFrom @UseCodec val: Payload` but the broader annotation
  surface should accept any `@ProtocolMessage` field that wants a
  user-supplied codec.

### Existing pieces to compose, not rebuild

- `Payload` interface already exists at
  `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/Codec.kt:117`
  as an empty marker. No work needed there.
- §8 raw-bytes ban already carves out `Payload`-typed fields in
  `walkType` (`ProtocolMessageProcessor.kt`); a `Payload` field
  doesn't trip the ByteArray/ReadBuffer rejection.
- `LengthFromString` decode pattern (slice 4) is the closest analog
  for what slice 10a needs: bounded read of a value via a
  user-provided codec. The shape is identical — substitute
  `<UserCodec>.decode(buffer, context)` for `buffer.readString(length, Charset.UTF8)`.
- `@DispatchOn` aggregator dispatcher (slice 6) is the closest
  analog for the slice 10d throwing-default lambda pattern.
  Throwing-default is just `else -> throw DecodeException(...)`
  in the dispatcher's `when`.

## Other potential follow-ons (not pre-merge)

These were noted during the work but aren't required for the PR
that lands the current branch. Worth considering after Stage H:

### Batch optimizations

The current emit reads byte-by-byte in several hot paths. The
buffer module exposes bulk operations (`readBytes`, `xorMask`,
`fill`, etc.) that could replace per-byte loops. Specific
candidates:

- **`appendDecodeRemainingBytesScalarList`** (slice 7b) reads
  `buffer.readUByte()` in a `while (position < limit)` loop. For
  a 1000-byte list that's 1000 method calls. Could be a single
  `readBytes(remaining)` then iterate the resulting buffer.
  Likely subsumed by Stage H's typed-payload mechanism for the
  arbitrary-bytes case; might still apply for `List<UByte>` /
  `List<Byte>` element types.
- **`appendManualScalarDecode`** (existing pre-Stage-E code) reads
  N bytes one at a time then assembles via shift+OR. Could batch
  the N reads.
- **`appendPeekPrefixAssembly`** similar — one `peekByte` per
  prefix byte.
- **`appendPeekFixedScalar`** (slice 6.5) for UShort/UInt similar.

These need profiler data before optimizing — picking the wrong
candidates wastes effort. A proper "perf slice" would start with
benchmarks against `:buffer-codec-test:jvmTest` representative
fixtures and identify the actual hotspots.

### Per-platform fast paths

The runtime exposes JVM-specific fast paths (FFM Arena on JDK 21+,
Unsafe.allocateMemory pre-21). Generated codecs don't currently
take advantage of these. Worth investigating after Stage H stabilises.

## How to start the next session

1. Read `PHASE_9_RESET.md` §"Stage H — Payload SAM via MQTT v5
   PUBLISH" for the spec.
2. Read this file's "Stage H — open design questions" section.
3. Pick a sequencing (recommended: phased starting with slice 10a).
4. Re-derive the slice 10a design against today's `FieldSpec`
   hierarchy + `LengthSource` shape. Don't copy from the reverted
   worktree without verifying against the current architecture.
5. The `@UseCodec` resolution mechanism is the first design lock
   needed; until that's settled, slice 10a's emit shape is
   under-determined.
