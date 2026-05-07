# Phase J.M.5 — slice 14c proper handoff (MQTT v3/v5 substitution)

**Read this top-to-bottom before writing code.** Slice 14c-prep landed
the standalone-shape `@FramedBy(after = "X")` emit and the non-generic
`@DispatchOn` dispatcher integration, validated against
`Slice14cFramedDispatch` (a `UByte`-header sealed-parent probe with two
variants — fixed-body and `@LengthPrefixed` body). This handoff covers
the MQTT v3/v5 substitution that 14c-prep was meant to unblock.

**Critical:** the original 14c handoff (`PHASE_J_M_5_SLICE_14C_HANDOFF.md`)
described 14c proper as "mostly mechanical" once 14c-prep was green. That
framing is wrong. Both `MqttPacket<out P : Payload>` and
`MqttV5Packet<out P : Payload>` are **generic** sealed parents, and
14c-prep only handles the **non-generic** dispatcher. Three additional
emit/integration shapes need design + implementation before any v3/v5
fixture is touched. Those shapes are the open questions this handoff
asks the next session to walk through with the user.

## Branch state at start of 14c proper

Two commits land 14b and 14c-prep:

```
<sha-14c-prep>  Phase J.M.5 slice 14c-prep: sealed-parent + after="X" emit
c4ec313b       Phase J.M.5 slice 14b: @FramedBy capability + remove @DerivedLength
851332ca       Phase J.M.5 slice 14a: @DerivedLength annotation, MVP (fixed-size suffix)
```

Test baseline at end of 14c-prep:

```
:buffer-codec-test:jvmTest    = 494   (489 + 5 Slice14cFramedDispatch tests)
:buffer-codec-processor:test  = 68
:buffer-flow:jvmTest          = 36
```

ktlint clean. Cross-target compile clean (linuxX64 / js / wasmJs).

## What 14c-prep shipped (already in the tree)

You don't need to redo any of this; reference it as you implement
14c proper.

- **Q1 — inherited `@FramedBy` detection.** `detectFramedBy` (in
  `CodecEmitter.kt`) walks declared `superTypes` for sealed-interface
  parents carrying `@FramedBy`. Helpers `isFramedByAnn` and
  `parseFramedBy` factor the annotation parsing. So a variant like
  `data class Connect(...) : MqttPacket<Nothing>` inherits framing from
  the parent's annotation.
- **Q2 — `after = "X"` decode.** `buildFramedByDecodeFun` reads the
  named header field before the prefix (so the local binds positionally
  for the constructor), then runs the strict-bound body in a
  `try { ... } finally { setLimit }`.
- **Q3 — `after = "X"` encode.** `buildFramedByEncodeFun` computes
  `headerWireWidth` from the after-field's wire shape (Scalar /
  ValueClassScalar via `framedByHeaderWireWidth`) and threads it plus a
  `writeHeader` lambda into `FramedEncoder.encode`. Body lambda skips
  the after-field. The shared `appendEncodeField` helper centralizes
  the per-field encode dispatch across `buildEncodeFun`,
  `buildFramedByEncodeFun`'s body lambda, and the `writeHeader` lambda.
- **Q4 — `peekFrameSize` for `@FramedBy`.** New
  `buildFramedByPeekFrameFun` mirrors the slice-11
  `LengthPrefixedUseCodecList` walker: peek at
  `baseOffset + headerWireWidth`, drive `framingCodec.decode` against
  the non-consuming view, compute total = headerWireWidth +
  observedPrefixWidth + decodedValue.
- **Q5 — non-generic `@DispatchOn` dispatcher integration.**
  `DispatchOnDispatcherShape.framedBy: FramedByConfig?` captures the
  parent's `@FramedBy`. When set, `buildDispatchOnDispatcherFileSpec`
  routes through a non-generic-only branch that:
  - Drops the `Codec<Parent>` superinterface.
  - Emits `encode(value, context, factory): ReadBuffer` that routes
    by variant to `<Variant>Codec.encode(value, context, factory)`.
  - Skips `wireSize`.
  - Emits a single dispatcher-owned `peekFrameSize` walker (header
    wire width comes from `discriminatorInnerKind.width`).
  - `buildDispatchOnDecodeFun` conditionally omits `KModifier.OVERRIDE`
    when `framedBy != null` (the dispatcher no longer extends `Codec`).
- **`Slice14cFramedDispatch` probe.** Sealed parent
  `@FramedBy(MqttRemainingLengthCodec::class, after = "header")` with
  `@DispatchOn(Slice14cTinyHeader::class)`, two variants (fixed-body A,
  `@LengthPrefixed`-body B). Five tests cover wire format, round-trips
  at 1-byte and 2-byte VBI prefix, and strict-bound rejection. Lives in
  `buffer-codec-test/.../slice14c/`.

## Why 14c proper isn't mechanical

`MqttPacket<out P : Payload>` and `MqttV5Packet<out P : Payload>` are
both generic sealed parents (the slice 10b/10d shape — Publish<P>
threads a constructor-injected payload codec). The 14c-prep emit only
handles the non-generic case: `buildDispatchOnDispatcherFileSpec`
routes to `buildGenericDispatchOnDispatcherTypeSpec` whenever
`payloadTypeParameter != null` — and that path has no `@FramedBy`
support. Same hole on the variant side: a generic variant
(`Publish<P>`) inherits `@FramedBy` from the parent, but
`buildFileSpec` routes generic variants to `buildGenericCodecTypeSpec`,
which doesn't know about framing.

Three things must be designed before v3/v5 fixtures are edited.

### Open question 1 — Generic dispatcher × `@FramedBy` emit

The current `class FooCodec<P : Payload>(payloadCodec: Codec<P>) : Codec<Foo<P>>`
shape needs a framed counterpart. Options to pick:

- **Option 1A:** Mirror the non-generic emit: drop `Codec<Foo<P>>`,
  encode signature `(value: Foo<P>, context, factory): ReadBuffer`, no
  wireSize, dispatcher-owned peek walker. Constructor-injected
  `payloadCodec` and per-generic-variant private codec instance fields
  stay unchanged. The aggregator companion stays unchanged. **Pro:**
  symmetric with non-generic shape, minimal new design surface.
  **Con:** any caller that had `Codec<MqttPacket<P>>` typed against the
  dispatcher breaks.
- **Option 1B:** Keep `Codec<Foo<P>>` superinterface; emit a
  throwing `encode(buffer, value, context)` stub plus the new
  `encode(value, context, factory): ReadBuffer` as a regular method.
  Callers using `Codec<MqttPacket<P>>` keep typechecking but trip at
  runtime if they invoke the `Codec.encode`. **Pro:** type-compatible
  with `:buffer-flow`'s current `asCodecConnection(codec: Codec<T>)`.
  **Con:** muddied contract — the slice 14b handoff Q5 explicitly
  rejected this (calls a throwing stub "worse than the explicit
  signature mismatch").
- **Option 1C:** Emit an `Encoder` adapter: keep `Codec<Foo<P>>` but
  override `encode` so it allocates a small buffer, copies the framed
  ReadBuffer's bytes, then writes them to the caller's WriteBuffer.
  **Pro:** API-compatible. **Con:** adds the memcpy that the slicing
  scheme exists to avoid; defeats the design's headline property.

### Open question 2 — Generic variant × inherited `@FramedBy` emit

Same dilemma at the variant level for `Publish<P>Codec`. The slice 10c
`Partial<P>` decode pattern must still emit (the aggregator depends on
it). Options:

- **Option 2A:** Drop `Codec<Publish<P>>` superinterface; emit framed
  encode/decode/peek; keep `Partial<P>` and `partial(buffer, context)`
  as regular members. Symmetric with the non-generic-variant treatment
  in 14c-prep.
- **Option 2B:** Keep `Codec<Publish<P>>` + throwing stub (same shape
  as 1B).

The variant-side choice should track the dispatcher-side choice; the
two live or die together.

### Open question 3 — `:buffer-flow` integration

`asCodecConnection(codec: Codec<T>, ...)` in
`buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/codec/`
takes a `Codec<T>`. Today
`CodecConnectionSmokeTest` wires up
`MqttPacketCodec(TextPayloadCodec)` as the codec parameter — that
breaks under Option 1A. Plus `decodeAggregating` is invoked off the
dispatcher's companion. Options:

- **Option 3A:** Add an adapter: the framed dispatcher exposes
  `asCodec(): Codec<MqttPacket<P>>` whose `encode(buffer, value, ctx)`
  invokes the framed `encode(value, ctx, factory)` against an internal
  factory and copies the resulting ReadBuffer's bytes into `buffer`.
  Same memcpy concern as 1C, scoped to the buffer-flow boundary
  instead of the codegen surface. Smoke tests update to call
  `MqttPacketCodec(TextPayloadCodec).asCodec()`.
- **Option 3B:** Generalize `asCodecConnection` (and any sibling
  utilities) to accept a "framed encoder + decoder" duck-typed pair
  rather than a `Codec<T>`. Either via an `Encoder<T>`-style narrower
  interface or a new `FramedConnectionCodec<T>` shape. Cleaner contract
  but more surface to maintain — `:buffer-flow` becomes aware of the
  framing distinction.
- **Option 3C:** Add a parallel `asFramedCodecConnection` factory that
  takes the framed dispatcher directly (no adapter, no generalization).
  `:buffer-flow` keeps its existing `asCodecConnection` for non-framed
  codecs, and framed protocols opt into a different builder. **Pro:**
  zero migration cost for non-framed protocols, no memcpy at the
  boundary. **Con:** two parallel APIs to maintain.

### Other things worth pinning down before coding

- **Wire-test coverage.** The handoff's "round-trips still pass" claim
  rests on the wire bytes not changing. The MQTT round-trip suite
  exercises `<header><VBI><body>` directly via
  `MqttPacketCodec.encode` / `.decode`. After 14c the encode signature
  changes shape; tests need to call the new entry point. Some tests
  use a hand-supplied `WriteBuffer`; those need to drop the allocation
  entirely. 23 v3 + matching v5 test files; bulk-edit-friendly but
  worth a final check before claiming "+0 net delta."
- **`peekFrameSize` consumers.** Any caller of
  `MqttPacketCodec.peekFrameSize(stream)` keeps the `(stream, baseOffset)
  : PeekResult` contract unchanged after 14c-prep — the dispatcher-
  owned walker emits the same shape. No caller-side changes needed.
- **`Codec<MqttPacket<P>>` references outside `:buffer-flow`.** A
  fresh grep against the substituted tree should confirm no other
  callsite types-against the dispatcher as a `Codec`. If one shows up,
  the `Option 1*` decision applies there too.

## Suggested AskUserQuestion walk

Walk the user through:
1. Pick a path for **Open question 1** (generic dispatcher).
2. Confirm the matching pick for **Open question 2** (generic variant)
   — they should track each other.
3. Pick a path for **Open question 3** (`:buffer-flow` integration).
4. Sanity-check the round-trip-test impact estimate (mostly mechanical
   constructor-arg drops + encode-call-site rewrites; the count was 23
   v3 + 11 v5 test files in the original handoff).

Then implement on top of those decisions. Don't start coding before
all three are answered — they interact (Option 1B/2B avoids the
:buffer-flow integration question entirely; Option 1A/2A forces it).

## Implementation roadmap (after design pick)

For every option-pair the rough roadmap is:

1. **Generic-dispatcher `@FramedBy` emit** —
   `buildGenericDispatchOnDispatcherTypeSpec` gains a framed branch
   gated on `shape.framedBy != null`. Reuse the
   `buildFramedByDispatchOnEncodeFun` /
   `buildFramedByDispatchOnPeekFun` shape from 14c-prep, parameterized
   by `parentTypeRef` (which is already
   `parentClassName.parameterizedBy(P)` for the generic case).
2. **Generic-variant `@FramedBy` emit** — `buildFileSpec` gains a
   `shape.framedBy != null && shape.payloadTypeParameter != null`
   branch routing to a new `buildGenericFramedByCodecTypeSpec`. Mirror
   the fields of `buildGenericCodecTypeSpec` but with framed
   encode/decode/peek and no wireSize. The slice 10c `Partial<P>` /
   `partial(buffer, context)` emit stays — those touch decode only.
3. **`@FramedBy` annotation on `MqttPacket`/`MqttV5Packet`.** Add
   `@FramedBy(MqttRemainingLengthCodec::class, after = "header")` to
   the sealed parent declarations.
4. **Drop `remainingLength` from 14 v3 + 14 v5 variants.**
   Mechanical. Each variant carries
   `@UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt`
   today; remove that parameter from the primary constructor.
5. **Update test sites.** Drop `remainingLength = ...` constructor
   args; switch encode call sites to the new
   `(value, context, factory): ReadBuffer` signature. ~23 v3 + ~11 v5
   test files.
6. **Update `:buffer-flow`.** Per Option 3 pick.

### Verification

```bash
./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
# Expected: 494 / 68 / 36 (unchanged from 14c-prep — wire bytes don't
# change, only data class shape and encode signature).

./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck

./gradlew :buffer-codec-test:compileKotlinLinuxX64 \
          :buffer-codec-test:compileKotlinJs \
          :buffer-codec-test:compileKotlinWasmJs
```

## Prompt to start the next session

> **Resume Phase J.M.5 — implement slice 14c proper (MQTT v3/v5
> substitution).**
>
> Read in order: `PHASE_J_M_5_SLICE_14C_PROPER_HANDOFF.md`
> top-to-bottom, the slice 14c-prep diff (`git show <sha-14c-prep>` —
> the inherited `@FramedBy` detection, the `after = "X"` emit shape,
> the dispatcher integration), and `Slice14cFramedDispatch` /
> `Slice14cFramedDispatchCodecTest` for the green probe shape.
>
> Confirm green baseline before implementing:
>
> ```
> ./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
> ./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck
> ```
>
> Expected `494 / 68 / 36`. ktlint clean.
>
> **Before coding, walk the three open questions with the user via
> `/AskUserQuestion`:**
> 1. Generic dispatcher × `@FramedBy` emit (Options 1A/1B/1C).
> 2. Generic variant × inherited `@FramedBy` emit (Options 2A/2B —
>    should track the dispatcher pick).
> 3. `:buffer-flow` integration (Options 3A/3B/3C).
>
> Don't start coding until all three are answered. Then follow the
> roadmap (generic emit work first, then `@FramedBy` on the sealed
> parents, then drop `remainingLength` from variants + test sites, then
> `:buffer-flow` integration).
>
> Verify: tests `494 / 68 / 36` (unchanged from 14c-prep — wire bytes
> don't change), ktlint clean, cross-target compile clean.

## What this handoff is NOT

- Not authorization to skip the AskUserQuestion walk. The three open
  questions interact; making the wrong pick on (1) without considering
  (3) wastes a substitution attempt.
- Not authorization to start coding the substitution before the
  generic-dispatcher / generic-variant emit lands. The 28 production
  fixtures must NOT be the first time `@FramedBy` exercises the
  generic path — that's the same lesson 14c-prep's probe enforced for
  the non-generic dispatcher, scaled up.
- Not a redesign of `@FramedBy`'s semantics. The 9 questions from the
  14b handoff and the 5 emit questions from the 14c-prep handoff stand;
  this handoff only opens design space for the generic-dispatcher
  combination that those handoffs didn't anticipate.
- Not a green light to pick Option 1B/2B silently. The slice 14b
  handoff Q5 explicitly rejected the throwing-stub shape. If the user
  picks 1B, capture the rationale (presumably "buffer-flow API
  compatibility outweighs the contract muddle for now") and link it
  back to that decision.
