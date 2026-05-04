# Stage H Resume Briefing

This document briefs the next session on the work landed on
`feature/directional-codec` and the design surface Stage H opens.
Read `PHASE_9_RESET.md` first — Stage H's spec lives in §"Stage H —
Payload SAM via MQTT v5 PUBLISH" of that document. This file is the
incremental delta and the open design questions.

## Branch state

- Branch: `feature/directional-codec`, head `a8f5225` (working tree
  ahead by the slice 10g smoke-test landing — see "Slice 10g shape"
  below; not yet committed). Not pushed.
- 27 stacked commits since the last `main`-merged commit (`d83a534`)
  once 10g commits. All commits are individually buildable and
  individually green.
- **Stage H is feature-complete and acceptance #4 is closed.** Every
  PHASE_9_RESET §Stage H capability has landed (slices 10a, 10b,
  10c, 10d, 10d.5, 10e, 10f) and the `:buffer-flow` smoke test
  (slice 10g, this session) closes acceptance #4: PUBLISH frames
  flow through `Connection<MqttPacket<…>>` end-to-end via the new
  `ByteStream.asCodecConnection(...)` bridge. Remaining work is the
  `mqtt` and `websocket` cutover (PHASE_9_RESET §"Non-goals") and
  PR merge.
- Test counts at head:
  - `:buffer-codec-test:jvmTest` — 204 tests, all pass.
  - `:buffer-codec-processor:test` — 46 tests, all pass.
  - `:buffer-flow:jvmTest` / `:jsNodeTest` / `:wasmJsNodeTest` /
    `:linuxX64Test` — 4 new `CodecConnectionSmokeTest` tests on
    each, 16/16 pass. Slice 10g acceptance.
  - `:buffer-codec-test:compileKotlin{Js,WasmJs,LinuxX64}` — all
    succeed (slice 10e proves expect/actual resolution
    end-to-end across every configured target).
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
| `5d878aa` | — | Doc: STAGE_H_RESUME briefing for slice 10d (now superseded by this file) |
| `5b5d9f9` | H.10d | Generic-aware `@DispatchOn` dispatcher — `Http2Frame<out P : Payload>` absorbing `Http2DataFrame` |
| `fc5f46d` | — | Doc: STAGE_H_RESUME briefing for slice 10f (now superseded by this file) |
| `f728700` | H.10f | `MqttPacket<out P : Payload>` + `Publish<P>` variant — lifts the `@RemainingLength` + typed payload carve-out |
| `f715849` | — | Doc: STAGE_H_RESUME briefing for slice 10e (now superseded by this file) |
| `51e0042` | H.10e | `@UseCodec` `expect`/`actual` linker-resolution lock + `MqttCodec<P>` typealias |
| `f180480` | — | Doc: STAGE_H_RESUME briefing for slice 10d.5 (now superseded by this file) |
| `d84ddc7` | H.10d.5 | Lambda-aggregator `decodeAggregating(...)` on generic dispatcher companion |

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
4. ✅ **Slice 10d (`5b5d9f9`)** — generic-aware `@DispatchOn`
   dispatcher. `Http2Frame` lifts to `<out P : Payload>`; the
   absorbed `Data<P : Payload>` variant routes through a
   constructor-injected `payloadCodec`. `<Nothing>`-typed
   variants (`Settings`, `Ping`, `WindowUpdate`) are unchanged
   on the wire and reachable through any `<P>` instantiation
   via covariance. The lambda-aggregator API and the MqttPacket
   lift are deferred (see slice 10d.5 / 10f below).
5. ✅ **Slice 10d.5 (`d84ddc7`)** — lambda-aggregator
   `decodeAggregating(buffer, context, on<Variant> = ...)` on the
   generic dispatcher's companion object. Per-variant lambda
   parameters with throwing-default attribution per row 17
   (`fieldPath = "<Parent>.<Variant>.handler"`). Wraps slice 10c's
   `<VariantCodec>.partial<P>(...)` machinery in a single dispatch
   call so consumers can pick the payload codec per call without
   pre-instantiating the dispatcher class.
6. ✅ **Slice 10f (`f728700`)** — `MqttPacket<out P : Payload>` +
   `Publish<P>` variant. Lifts the slice 10c
   `@RemainingLength` carve-out by capturing the outer buffer
   limit on `Partial` and restoring it via try/finally on
   `complete`. Closes PHASE_9_RESET §Stage H acceptance #1
   through the MQTT control-packet sealed.
7. ✅ **Slice 10e (`51e0042`)** — `@UseCodec` `expect`/`actual`
   linker-resolution lock + `MqttCodec<P>` typealias. Promoted the
   deferred-decisions row to PHASE_9_RESET Locked Decisions row 21
   ("direct call, Kotlin linker resolves; KSP doesn't inspect
   platform actuals"). Slice 10e doctrine vector
   `RemoteCommandPayloadCodec` proves it end-to-end via
   `expect`/`actual` declarations across `jvmMain`/`jsMain`/
   `wasmJsMain`/`nativeMain`. `typealias MqttCodec<P> =
   MqttPacketCodec<P>` is a hand-written one-liner per
   PHASE_9_RESET §"Stage H — Payload SAM".

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

### Slice 10d shape — landed

- **Detection rule.** The dispatcher emits as a generic class
  iff the sealed parent has exactly one type parameter with
  `Payload` upper bound (`detectPayloadTypeParameter` — same
  helper as slice 10b for data classes; reused for sealed
  parents). Variance (`out P`) is ignored — the helper checks
  bounds independently of variance, so `<P : Payload>` and
  `<out P : Payload>` both qualify.
- **Per-variant generic detection.** Each variant runs through
  the same `detectPayloadTypeParameter`. A variant with `<P :
  Payload>` becomes a "generic variant" — the dispatcher
  constructs `<VariantName>Codec(payloadCodec)` once in the
  primary constructor, stores it as a private property, and
  references the field at every emit site. A `<P : Payload>`
  variant under a non-generic sealed parent is a shape error
  (the parent has no codec to thread); the analyze path
  `return null`s silently and the validator surfaces the
  diagnostic.
- **Encode `is`-check + cast.** Generic variants smart-cast
  to their star-projected form (`is Foo.Data<*>`) and then
  explicit-cast to `Foo.Data<P>` at the variant codec call
  site (`@Suppress("UNCHECKED_CAST")`). The cast is statically
  safe by construction: the dispatcher's `value: Foo<P>`
  matched as `Foo.Data<*>` must be `Foo.Data<R : P>` for some
  R, which the variant codec's `Codec<P>` accepts via the
  `out P` covariance on the sealed parent.
- **Field naming.** `<VariantName>Codec` instance fields use
  camelCase variant name + `Codec` (`DataCodec` → `dataCodec`).
  Symmetric with the slice 10b `payloadCodec` convention.

### Slice 10f shape — landed

- **Outer-limit capture in `Partial`.** When the partial decode
  body crosses `@RemainingLength` (`appendDecodeRemainingLength`
  emits `__<rlName>OuterLimit = buffer.limit()` then `setLimit(
  position + RL)`), the Partial constructor takes the outer
  limit as a `private val outerLimit: Int` field; the partial
  entry function wires `outerLimit = __<rlName>OuterLimit` into
  the constructor call.
- **`complete` try/finally.** When the shape carries
  `@RemainingLength`, `Partial.complete(...)` wraps the payload
  decode + constructor call in `try { … } finally {
  buffer.setLimit(outerLimit) }`. The payload decode runs inside
  the var-int-narrowed bound (correct for payload bounding); the
  outer limit is restored even if the user codec throws.
- **`shouldEmitPartial` lift.** The slice 10c `@RemainingLength`
  skip is removed — `Partial` is now emitted unconditionally
  whenever a `RemainingBytesPayload` field is present. The two
  paths (with/without RL) differ only in the `outerLimit` field
  + try/finally body; the no-RL path is unchanged.

### Slice 10e shape — landed

- **`@UseCodec` resolution doctrine:** direct call, Kotlin linker
  resolves; KSP doesn't inspect platform actuals (Locked Decision
  row 21 in PHASE_9_RESET). The processor accepts `expect object`
  as a valid `@UseCodec` target without modification — KSP reports
  `classKind == ClassKind.OBJECT` for both expect and actual
  declarations, and the validator's `ClassKind.OBJECT` check (line
  786) suffices.
- **Multiplatform fixture (`RemoteCommandPayloadCodec`):**
  `expect object` in commonMain + `actual object` in `jvmMain` /
  `jsMain` / `wasmJsMain` / `nativeMain`. Each actual delegates
  to a shared `internal RemoteCommandPayloadCodecImpl` so wire
  bytes are identical across platforms; only the resolution path
  differs. Compiles cleanly on all four target sets.
- **`MqttCodec<P>` alias:** hand-written `typealias MqttCodec<P> =
  MqttPacketCodec<P>` in `buffer-codec-test/.../mqtt/MqttCodec.kt`.
  Hand-written rather than emitter-generated: one line of
  consumer-side code doesn't justify processor complexity, and
  emitting the typealias would force a naming-collision burden
  on every consumer that uses the generated codec name directly.

### Slice 10g shape — landed (this session)

Acceptance #4 close: `:buffer-flow` smoke test exercising the
codec-emitter API through `Connection<MqttPacket<TextPayload>>`.
Not a codec-emitter slice; an integration vector that exercises
slice 10c's `Partial`, slice 10d's generic dispatcher, slice 10d.5's
`decodeAggregating`, and the streaming `peekFrameSize` path
(emitted across every Stage F+G slice) end-to-end through the
flow boundary.

- **API added.** Single new `commonMain` extension at
  `:buffer-flow/.../codec/CodecConnection.kt` —
  `fun <T : Any> ByteStream.asCodecConnection(codec, pool, scope,
  id, byteOrder, sendBufferSize): Connection<T>`. Single `pool`
  parameter serves both directions: send via
  `BufferFactory.Default.withPooling(pool)` (one
  `factory.allocate(...).use { codec.encode(); byteStream.write() }`
  per send), receive via `StreamProcessor.create(pool, byteOrder)`
  driving a `peekFrameSize` → `readBufferScoped(decode)` loop on a
  background coroutine that pushes decoded values to an unlimited
  `Channel<T>`.
- **Module wiring.** `:buffer-flow:commonMain` adds
  `compileOnly(project(":buffer-codec"))` so the bridge surface is
  optional — consumers using only `Connection` / `StreamMux` pay
  no codec-module compile cost; consumers using
  `asCodecConnection` add `:buffer-codec` to their own
  dependencies. `:buffer-flow:commonTest` adds
  `:buffer-codec` + `:buffer-codec-test` so the smoke test reaches
  the existing `MqttPacket` / `MqttPacketCodec` /
  `TextPayloadCodec` fixtures.
- **Why one pool, not pool + factory.** Investigated:
  `BufferFactory.allocate()` does support deterministic cleanup
  via `.use { freeNativeMemory() }` (CloseableBuffer interface),
  so factory-allocated buffers can absolutely be released. The
  refcount/slice tracking that the receive loop needs is on
  `PooledBuffer`, not on `BufferPool` itself — a `withPooling`
  factory produces those same `PooledBuffer` instances. The
  remaining blocker is purely `StreamProcessor.create`'s
  signature: it takes `BufferPool` directly, not a
  `PoolingFactory`. Caller-supplies one pool, the bridge derives
  a pooling factory internally for the send path.
- **Test fixture: `TextPayload`, not `JpegImage`.**
  `JpegImageCodec.decode` ends with `readByteArray(remaining)`,
  which allocates a `ByteArray` for the payload data. That's
  fine for codec-emitter unit tests but would muddy the smoke
  test's "framing path is zero-`ByteArray`" claim. `TextPayload`
  uses `readString` / `writeString`, which is zero-`ByteArray`
  on JVM/Apple/JS. The WASM/`nonJvm` `writeString` carve-out
  (Locked Decision row 16) is acknowledged in a comment in the
  smoke-test file.
- **Tests landed (4 × 4 platforms = 16 / 16 pass):**
  1. `publishRoundTripsThroughConnection` — encode + send a
     `MqttPacket.Publish<TextPayload>` from one in-memory
     `Connection<MqttPacket<TextPayload>>`, receive it on the
     other; `assertEquals(publish, received)`.
  2. `payloadFreeVariantsRoundTrip` — `Connect` + `Disconnect`
     (typed `MqttPacket<Nothing>`) flow through
     `Connection<MqttPacket<TextPayload>>` via covariance.
  3. `peekFramingHandlesSplitWrites` — drip-feed an encoded
     `Publish` byte-by-byte; the receive loop surfaces exactly
     one decoded message after the final byte. Confirms the
     `peekFrameSize` → `readBufferScoped(decode)` loop survives
     fragmented inbound chunks.
  4. `aggregatorPathTopicKeyed` — sends a `Publish` through the
     standard `asCodecConnection` bridge, then on the receive
     side calls `MqttPacketCodec.decodeAggregating` directly
     against a raw `StreamProcessor` with an
     `onPublish = { partial -> partial.complete(codecForTopic) }`
     handler. Demonstrates the slice 10d.5 seam works at the
     edge for topic-keyed payload-codec selection.
- **In-memory `ByteStream` pair.** Smoke-test-private helper
  (not promoted to commonMain). A `Channel<ReadBuffer>` per
  direction; `read` is `channel.receive()`, `write` is
  `channel.send(buffer)`. Small enough that promoting it to a
  testing utility is premature — wait for a second consumer.
- **Coverage.** `jvmTest`, `jsNodeTest`, `wasmJsNodeTest`,
  `linuxX64Test` all pass. Apple targets, Android, Windows
  native are unreachable from the Linux dev host but use the
  same commonMain source — same expectation.
- **Allocation-tracking enforcement deferred.** A JFR-driven
  zero-`[B` test mirroring `:buffer-codec-test`'s harness was on
  the original plan as test #5; deferred because lifting the JFR
  harness across module boundaries is its own scope. The
  framing-side codec emitters are already JFR-validated in
  `:buffer-codec-test`; the bridge's allocation guarantee is the
  same emitter contract reused, plus one factory allocation per
  send (which is the documented send-side cost). Promote to a
  proper test if/when a regression surfaces.

### Slice 10d.5 shape — landed

- **Lambda return type: variant, not parent.** The discriminator's
  promise is "this byte means PUBLISH"; the lambda's job is to
  complete the matched variant from a `Partial`, not to substitute
  a different variant. Returning the variant (`Foo.Publish<P>`)
  gives the dispatcher's `when` branch a typed result that
  assigns to `Foo<P>` via `out P` covariance with no cast. The
  "consumer wraps the result" use case is served by wrapping
  outside the dispatcher call.
- **Lambda arg type: `<VariantCodec>.Partial<P>`.** Slice 10c's
  `Partial` is the natural seam — the consumer inspects headers
  on the `Partial`, then completes via `partial.complete(
  payloadCodec)` choosing the codec at the call site.
- **Companion-side placement.** Mirrors slice 10b/10c's
  `partial<P>(...)` convention. The aggregator's `<P : Payload>`
  is a function-level type variable; consumers call
  `<DispatcherCodec>.decodeAggregating<P>(...)` without
  instantiating the dispatcher class. The aggregator never
  invokes the constructor-injected `payloadCodec` — the per-call
  lambda supplies the codec.
- **Throwing-default lambdas.** Each payload-bearing variant's
  lambda defaults to a `DecodeException` with `fieldPath =
  "<Parent>.<Variant>.handler"`. Consumers override only the
  variants they expect. `bufferPosition` is `-1` because parameter
  defaults can't reference function locals — the meaningful
  attribution is `fieldPath` (which variant needed a handler).
- **Method shape: separate function name (`decodeAggregating`)
  rather than `decode` overload.** `decode(buffer, context)` is
  the `Codec<T>.decode` interface override; an overload would
  collide. A separate name keeps the `Codec<T>` interface pristine
  and signals at the call site that this is a different decode
  pathway with different semantics.
- **Generic dispatchers only.** Non-generic dispatchers have no
  payload-bearing variants by construction.

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

**Stage H is feature-complete and acceptance #4 is closed.** Every
PHASE_9_RESET §Stage H capability has landed (slices 10a, 10b,
10c, 10d, 10d.5, 10e, 10f) and the `:buffer-flow` smoke test
(slice 10g) verifies PUBLISH frames flow through
`Connection<MqttPacket<…>>` end-to-end. The remaining work is
the `mqtt` and `websocket` cutovers and PR merge.

1. **Commit the slice 10g work.** Working tree carries:
   - `buffer-flow/build.gradle.kts` — `compileOnly(":buffer-codec")`
     on commonMain + commonTest deps for fixtures.
   - `buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/codec/CodecConnection.kt`
     — the bridge.
   - `buffer-flow/src/commonTest/kotlin/com/ditchoom/buffer/flow/codec/CodecConnectionSmokeTest.kt`
     — 4 tests, 16/16 pass across jvm/js/wasmJs/linuxX64.

   Suggested commit message stem: `Stage H slice 10g: :buffer-flow
   smoke test closes acceptance #4 — PUBLISH through
   Connection<MqttPacket<…>>`.
2. **PR merge prep.** Read `PHASE_9_RESET.md` §"Stage H —
   Payload SAM via MQTT v5 PUBLISH" for the overall acceptance
   list. All four acceptance criteria are now met:
   - #1 typed-payload `@UseCodec` round-trip (slice 10a vector).
   - #2 bare-`Payload` escape hatch (slice 10b
     `MqttPublishV3<P : Payload>` vector).
   - #3 throwing-default fires on missing handler
     (`MqttPacketAggregatorCodecTest.aggregatorThrowsWhenPublishHandlerOmitted`).
   - #4 `:buffer-flow` PUBLISH round-trip (slice 10g, this session).
3. **Next stage of work — `mqtt` and `websocket` repo cutovers
   (PHASE_9_RESET §"Non-goals" carve-out).** Both repos are
   currently frozen on the legacy codec path. Cutover steps:
   - Republish `:buffer-codec` + `:buffer-codec-processor` to
     mavenLocal (PHASE_9_RESET §Constraints flags this is post-
     rewrite, not load-bearing during the rewrite).
   - In each downstream repo, replace hand-written codecs with
     `@ProtocolMessage`-annotated data classes per the doctrine
     vectors in `:buffer-codec-test`. The MQTT v3.1.1 / v5 wire
     vectors in
     `:buffer-codec-test/.../protocols/mqtt/MqttPacket.kt` and
     `:buffer-codec-test/.../protocols/payload/MqttPublishV3.kt`
     are the closest analog for the `mqtt` repo's models.
   - Wire each downstream's `Connection` boundary through
     `ByteStream.asCodecConnection(codec, pool, scope)` from
     this session's bridge.
4. The codec-emitter API surface for downstream consumers
   (unchanged):
   - **`<Codec>.decode(buffer, context)`** — standard decode via
     constructor-injected `payloadCodec` (slices 10a/10b/10d/10f).
   - **`<Codec>.partial(buffer, context)`** (slice 10c, member on
     object dispatchers) and **`<Codec>.partial<P>(buffer,
     context)`** (slice 10c, companion on generic dispatchers) —
     header decode + deferred payload completion.
   - **`<DispatcherCodec>.decodeAggregating<P>(buffer, context,
     on<Variant> = ...)`** (slice 10d.5) — per-variant lambda
     aggregator with throwing-default attribution. Wraps the
     `Partial` machinery in a single dispatch call.
   - **`ByteStream.asCodecConnection(codec, pool, scope, id,
     byteOrder, sendBufferSize): Connection<T>`** (slice 10g) —
     turns a `ByteStream` into a typed `Connection<T>` using the
     codec for framing.
5. The remaining followup in PHASE_9_RESET deferred-decisions:
   `@RemainingLength` decompose into `@VarByteInt + @BoundsRemaining`.
   Drive from a second var-int-using vector (Stage G follow-up),
   not as part of Stage H closeout.
