# Stage H Resume Briefing

This document briefs the next session on the work landed on
`feature/directional-codec` and the design surface Stage H opens.
Read `PHASE_9_RESET.md` first — Stage H's spec lives in §"Stage H —
Payload SAM via MQTT v5 PUBLISH" of that document. This file is the
incremental delta and the open design questions.

## Branch state

- Branch: `feature/directional-codec`, head `18c6012`. Not pushed.
- 18 stacked commits since the last `main`-merged commit (`d83a534`).
  All commits are individually buildable and individually green.
- Test counts at head:
  - `:buffer-codec-test:jvmTest` — 186 tests, all pass.
  - `:buffer-codec-processor:test` — 46 tests, all pass.
  - `:buffer-codec-test:jsNodeTest` — pre-existing failure
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
| `ee7bb81` | H.10a | Typed-payload `@UseCodec` via `@RemainingBytes` — MqttPublishV3 round-trip |
| `7b2f00f` | H.10b | Generic-bounded payload slots — `MqttPublishV3<P>` + `Http2DataFrame<P>` |
| `0660008` | — | Doc: STAGE_H_RESUME briefing for slice 10c (now superseded by this file) |
| `18c6012` | H.10c | `Partial` decode pattern — `Codec.partial(...)` + `Partial.complete(...)` for typed-payload header decode |

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
- `@UseCodec(KClass<*>)` — references a Kotlin `object` declaration
  implementing `Codec<T>`. Slice 10a wires the
  `@RemainingBytes @UseCodec val: P` (P : Payload) composition;
  other compositions (`@LengthFrom @UseCodec`,
  `@LengthPrefixed @UseCodec`, bare `@UseCodec`) defer to later
  Stage H slices and produce explicit "not yet supported"
  diagnostics.

### Generated decode pathways

Two decode entry points exist on every codec carrying a typed payload
field:

1. **Full decode** (`Codec.decode(buffer, context)`): always emitted.
   Reads headers + payload in a single call. Slice 10a uses the
   `@UseCodec`-pinned codec; slice 10b uses the constructor-injected
   `payloadCodec`.
2. **Partial decode** (slice 10c — emitted whenever a
   `RemainingBytesPayload` field is present, except when the shape
   also carries `@RemainingLength`): a `Codec.partial(buffer,
   context): Partial` (slice 10a member) or
   `Codec.Companion.partial<P>(buffer, context): Partial<P>` (slice
   10b companion) reads only the headers, captures the buffer +
   context, and returns a `Partial` whose `complete(...)`
   continuation runs the payload decode at the consumer's call
   site. `Partial.complete()` (slice 10a) takes no argument;
   `Partial.complete(payloadCodec: Decoder<P>)` (slice 10b) accepts
   the codec at completion time, decoupled from any surrounding
   codec instantiation.

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
- `FieldSpec.RemainingBytesPayload` — `@RemainingBytes` on a Payload-
  typed (slice 10a) or `<P : Payload>` type-parameter-typed (slice 10b)
  field. Carries `payloadType: TypeName` and
  `source: PayloadCodecSource` (sealed `UserCodecObject |
  ConstructorInjected`) — exhaustive `when` at every emit site
  decides whether the codec call uses a static object reference
  (`UserCodec.decode(...)`) or an instance field
  (`payloadCodec.decode(...)`).
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

## Stage H — slice progress

`PHASE_9_RESET.md` §Stage H specifies the vector and capability list
but not the implementation shape. The prior implementation lives in
`.claude/worktrees/agent-acbc960a6159a0113/` (49 reverted commits)
and predates the architectural refactors landed since
(`LengthSource`, `FieldSpec.FixedSize`, the sequential peek walk,
the wireOrder propagation, slice 10a/10b refactors). The prior
worktree is **reference, not specification** — re-derive each slice's
design against today's emitter shape.

### Capabilities required (from PHASE_9_RESET §Stage H)

1. ✅ **Slice 10a (`ee7bb81`)** — typed-payload `@UseCodec` against a
   `@RemainingBytes`-bounded Payload-typed field. Concrete-type
   resolution: `@UseCodec(JpegImageCodec::class)`.
2. ✅ **Slice 10b (`7b2f00f`)** — generic-bounded payload slots.
   `<P : Payload>` type parameter on the data class generates a
   parameterized codec class with constructor-injected `Codec<P>`.
   `PayloadCodecSource` sealed unifies slice 10a's `@UseCodec` and
   slice 10b's constructor-injected paths under one type-safe
   shape.
3. ✅ **Slice 10c (`18c6012`)** — `Partial` decode pattern. Emit
   `Codec.partial(buffer, context)` (member on slice 10a object,
   companion on slice 10b class) returning a nested `Partial`
   class carrying the header fields + a `complete(...)`
   continuation. Slice 10a `complete()` runs the
   `@UseCodec`-pinned codec; slice 10b
   `complete(payloadCodec: Decoder<P>)` takes the codec at the
   call site. This unblocks the `:buffer-flow` smoke test
   (PHASE_9_RESET acceptance #4) — flow can decode headers
   without committing to a payload type at the type-erased
   `Connection<MqttControlPacket<…>>` boundary.
4. ⏳ **Slice 10d — aggregator + generic-aware sealed dispatcher.**
   Emit `MqttControlPacketCodec.decode` taking one lambda per
   payload-bearing variant; missing handler throws with field-path
   attribution. Lifts `Http2Frame` to `<out P : Payload>` (with
   `Settings : Http2Frame<Nothing>`) and absorbs the standalone
   `Http2DataFrame<P>` from slice 10b as a sealed variant.
   Acceptance #3.
5. ⏳ **Slice 10e — `@UseCodec` `expect`/`actual` lock + `MqttCodec`
   alias.** Lock the cross-platform resolution decision (the
   deferred-decisions row "@UseCodec expect/actual resolution path"
   already leans toward "direct call, linker resolves"). Add the
   convenience alias.

### Slice 10a + 10b shape — landed

- **Annotation surface.** `@UseCodec(KClass<*>)` accepts any Kotlin
  `object` implementing `Codec<T>`. Slice 10a wires the
  `@RemainingBytes @UseCodec val: P` (P : Payload) composition;
  slice 10b's `<P : Payload>` shape uses no `@UseCodec` (the
  codec is a constructor parameter on the generated codec class).
- **`PayloadCodecSource` sealed.** Mirrors the slice 9 `LengthSource`
  doctrine. Two members: `UserCodecObject(codecType: ClassName)`
  and `ConstructorInjected(parameterName: String)`. Every emit site
  uses an exhaustive `when` to choose the codec receiver:
  `Foo.decode(...)` (object) vs `payloadCodec.decode(...)` (field).
- **Generic class emission.** When the data class declares
  `<P : Payload>`, `buildFileSpec` emits a `class FooCodec<P :
  Payload>(private val payloadCodec: Codec<P>) : Codec<Foo<P>>`
  instead of `object FooCodec`. `buildDecodeFun` /
  `buildEncodeFun` / `buildWireSizeFun` accept an optional
  `messageType: TypeName` override so the same builders produce
  parameterized references (`Foo<P>`) inside the generic class
  body.
- **Validator additions.**
  - `validateUseCodec` — Payload field without `@UseCodec` is a
    hard error; `@UseCodec` paired with non-`@RemainingBytes`
    framing is "not yet supported in slice 10a"; target must be a
    Kotlin `object` implementing `Codec<T>` for T = field type.
  - `validatePayloadTypeParameter` — at most one type parameter,
    single `Payload` upper bound, at least one `@RemainingBytes
    val: P` field uses it.
  - The two checks coordinate: a type-parameter-typed field is
    exempt from the "Payload requires `@UseCodec`" check (the
    constructor-injected codec is the resolution path), and
    `@UseCodec` on a type-parameter-typed field is mutually
    exclusive with the constructor-injected resolution.
- **wireSize.** Both slice 10a and 10b unconditionally return
  `WireSize.BackPatch` for messages containing a
  `RemainingBytesPayload` field. Promotion to runtime-Exact via
  cast (mirroring `LengthPrefixedMessage`) is a follow-on once a
  vector measurably benefits.
- **peekFrameSize.** `NoFraming`. The bound comes from the caller-
  set buffer limit; without `@RemainingLength` the codec genuinely
  can't peek the frame from a stream. Slice 10d's outer dispatcher
  will own peek by reading the var-int first.

### Vectors landed

- `MqttPublishV3Concrete` (slice 10a) — `JpegImage` payload via
  `@UseCodec(JpegImageCodec::class)`.
- `MqttPublishV3<P : Payload>` (slice 10b) — same wire format,
  generic codec instantiated per payload type. Tests cover both
  `JpegImage` and `TextPayload` instantiations.
- `Http2DataFrame<P : Payload>` (slice 10b) — RFC 7540 §6.1 DATA
  frame, **standalone** for now (not under `Http2Frame` sealed
  parent — that integration is slice 10d). Tests cover both
  `Http2BinaryPayload` (ByteArray) and `Http2OpaquePayload`
  (ULong).

### Slice 10c shape — landed

- **Trigger.** Emit `Partial` for every codec containing a
  `RemainingBytesPayload` field, gated by `shouldEmitPartial`.
  The gate carves out shapes that also carry `@RemainingLength`:
  the var-int sets `buffer.limit()` mid-decode, and a Partial
  whose header decode crosses the var-int would either inherit
  the narrowed limit (correct for payload bounding) or restore
  the outer limit (correct for caller cleanup). Picking one
  without a vector exercising both ergonomics is premature; the
  slice 10a/10b vectors don't combine `@RemainingLength` with the
  payload field, so the narrow loses no current capability. Lift
  this when slice 10d lands a vector exercising the combo.
- **`Partial` API shape (option (a) refined).** Nested class on
  the codec carrying header fields as `val`s + private
  `buffer`/`context`, with a `complete(...)` continuation that
  reads the payload from the captured buffer. Trust contract
  documented on the generated class: consumer must call
  `complete(...)` before any external buffer mutation
  (position/limit changes, release).
- **`PayloadDecoder<P>` SAM not introduced.** `Decoder<P>` is
  the same contract; a parallel SAM would be noise.
  `Partial.complete(payloadCodec: Decoder<P>)` directly accepts
  the runtime interface.
- **Slice 10b interaction (companion-object placement).** The
  slice 10b `partial<P>(...)` entry lives on the codec class's
  companion object with its own `<P : Payload>` type variable.
  Companion-side placement is required: a member-side
  `partial(...)` would force the consumer to first construct
  `Codec(somePayloadCodec)` just to call partial — defeating
  the slice 10b purpose of deferring the codec choice past the
  header decode. Tested via
  `slice10bPartialDoesNotRequireSurroundingCodecInstance`.
- **`PayloadEncoder<P>` not introduced.** Encode never needs a
  `Partial`-equivalent because the consumer always has the full
  message at encode time; the existing slice 10a/10b encode path
  covers it.
- **`:buffer-flow` integration.** Acceptance #4 needs the flow
  layer to thread a topic-keyed payload decoder past the
  type-erased `Connection<MqttControlPacket<…>>` boundary.
  `Partial.complete(payloadCodec: Decoder<P>)` is the seam: the
  flow's reader decodes headers via `partial`, looks up the
  payload codec by topic, and supplies it to `complete`. The
  smoke test itself is deferred — slice 10d's outer dispatcher
  must land first so the flow can resolve PUBLISH within the
  `MqttControlPacket` sealed.

### Slice 10d open design questions

The next session should re-derive these against the current
emitter shape (the slice 10a/10b/10c emit code, the
`PayloadCodecSource` and `PayloadTypeParameter` types, the
`@DispatchOn` aggregator from slice 6, and the `Partial`
infrastructure). The prior worktree is reference only.

- **Generic-aware sealed dispatch.** Today the slice 6 dispatcher
  emits `class MqttControlPacketCodec : Codec<MqttControlPacket>`
  with a `when` over variants. Slice 10d needs the parent to
  become `<out P : Payload>`: `MqttControlPacket<out P :
  Payload>`, with payload-bearing variants like `Publish<P> :
  MqttControlPacket<P>` and payload-free variants like
  `PingReq : MqttControlPacket<Nothing>`. The dispatcher's
  generated codec needs a constructor parameter `payloadCodec:
  Codec<P>` it threads to the typed variants and ignores for
  the others. Doctrine answer locked while landing 10b said
  this is the right path; the open question is the emit-time
  detection: does the validator key off "any direct sealed
  variant has a `<P : Payload>` type parameter," or off the
  presence of `@DispatchOn` plus a payload-bearing variant?
- **Aggregator API.** PHASE_9_RESET names "`MqttControlPacketCodec
  .decode` aggregator with throwing-default lambdas across every
  payload-bearing variant." Two emit shapes:
  (a) `decode(buffer, context, onPublish: (Partial<P>) ->
  MqttControlPacket<P>, ...)` — one parameter per
  payload-bearing variant, defaulting to a throwing lambda with
  field-path attribution per row 17. The user overrides only the
  variants they expect.
  (b) `decode(buffer, context, payloadCodec: Codec<P>)` — the
  generic codec class instance approach (no lambdas; the
  dispatcher uses the same codec for every payload-bearing
  variant). Less flexible but matches the slice 10b
  constructor-injected pattern.
  Tradeoff: (a) is the PHASE_9_RESET design, (b) is the
  natural extension of slice 10b.
- **`Http2Frame` lift to `<out P : Payload>`.** Slice 10b kept
  `Http2DataFrame<P>` standalone. Slice 10d needs to fold it
  into `Http2Frame` per the doctrine answer. The other
  variants (`Settings`, `Headers`, `Continuation`) become
  `: Http2Frame<Nothing>`. The validator must accept the
  shape; the emit must produce a generic dispatcher class
  that threads `payloadCodec: Codec<P>` to the DATA variant
  and supplies `Codec<Nothing>` (or just ignores) for the
  others.
- **`@UseCodec` `expect`/`actual` resolution lock.** Defer to
  slice 10e; not load-bearing for 10d.
- **Throwing-default field-path attribution.** Per row 17
  (`fieldPath` = `"<SealedParentSimpleName>.discriminator"`,
  `expected` = `"one of {0xNN, …}"`, `actual` = the unexpected
  byte). For 10d's per-variant default lambdas, the field-path
  pattern should be `"<SealedParentSimpleName>.<VariantName>"`
  with `reason` naming the missing handler.
- **`MqttCodec` convenience alias.** Slice 10e per
  PHASE_9_RESET — typealias for the dispatcher to ease
  consumer-facing names. Defer to 10e.

### Existing pieces to compose, not rebuild

- `Payload` interface already exists at
  `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/Codec.kt:117`
  as an empty marker. No work needed there.
- §8 raw-bytes ban already carves out `Payload`-typed fields in
  `walkType` (`ProtocolMessageProcessor.kt`); a `Payload` field
  doesn't trip the ByteArray/ReadBuffer rejection.
- Slice 10a's `appendDecodeRemainingBytesPayload` /
  `appendEncodeRemainingBytesPayload` and slice 10b's generic
  class emission are the building blocks for slice 10c. The
  `Partial` emit is "decode everything before the payload, then
  return the header + a deferred payload-decode call."
- `@DispatchOn` aggregator dispatcher (slice 6) is the closest
  analog for the slice 10d throwing-default lambda pattern.
  Throwing-default is just `else -> throw DecodeException(...)`
  in the dispatcher's `when`.

### Doctrine answers locked while landing 10a/10b

- **Q: Should Payload-typed fields without `@UseCodec` silently
  skip or hard-error?** Hard error. The validator emits a
  directed diagnostic naming the missing annotation; users get
  feedback instead of a non-emitted codec.
- **Q: How do generics compose with sealed dispatch?** The sealed
  parent becomes `<out P : Payload>`; variants without payload
  use `: Foo<Nothing>`; the dispatcher class takes `payloadCodec:
  Codec<P>` and threads it to generic variants. **Not landed
  yet** — slice 10b kept `Http2DataFrame` standalone; slice 10d
  will lift `Http2Frame` per this doctrine.
- **Q: Multiple type parameters?** Rejected by validator. Slice
  10b supports exactly one `<P : Payload>`. Multiple parameters
  wait for a real protocol that needs them.

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
   PUBLISH" for the overall Stage H spec.
2. Read this file's "Stage H — slice progress" section. Slices
   10a, 10b, and 10c are landed (`ee7bb81`, `7b2f00f`, `18c6012`);
   slice 10d (aggregator + generic-aware sealed dispatcher) is
   next.
3. Read the actual slice 10c code before designing 10d — the
   `Partial` nested class, `partial(...)` companion-object entry,
   and `shouldEmitPartial`/`partialFieldTypeName` helpers are the
   building blocks the slice 10d aggregator composes with. The
   slice 6 `@DispatchOn` aggregator dispatcher (the closest
   analog for the throwing-default lambda pattern) lives in
   `buildDispatchOnDispatcherFileSpec`.
4. Resolve slice 10d's open design questions (this file's "Slice
   10d open design questions" section) before implementing. The
   generic-aware sealed dispatch shape and the aggregator API
   are the first design locks needed.
5. Re-derive the slice 10d emit design against today's emitter
   shape — don't copy from the reverted worktree without
   verifying.
