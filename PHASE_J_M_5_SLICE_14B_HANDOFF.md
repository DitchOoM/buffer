# Phase J.M.5 — slice 14b/14c handoff after design pass

**Read this top-to-bottom before writing code.** The design pass for
`@FramedBy` (the structural replacement for slice 14a's `@DerivedLength`)
is complete. The next session implements; do not re-litigate decisions.

## Branch state

Branch HEAD `851332ca` on `feature/directional-codec` (Phase J.M.5 slice
14a). Test baseline:

```
:buffer-codec-test:jvmTest    = 486
:buffer-codec-processor:test  = 63
:buffer-flow:jvmTest          = 36
```

ktlint clean across `:buffer-codec-test` and `:buffer-codec-processor`.
Cross-target compile clean (linuxX64 / js / wasmJs).

## What landed in the design pass

Nine questions resolved through a `/AskUserQuestion` walk. Each answer
below is final — implementation should follow without re-opening.

### Q1 — Annotation shape

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FramedBy(
    val codec: KClass<out BoundingLengthCodec<UInt>>,
    val after: String = "",
)
```

Class-level. Single annotation, two params. Lives in `:buffer-codec`
under `com.ditchoom.buffer.codec.annotations`.

### Q2 — Prefix position on the wire

Explicit `after = "fieldName"`. Names the sibling field that the prefix
sits immediately after; `after = ""` (default) means prefix at offset 0.
Validator enforces: the named field exists, has Exact wire width, and is
the discriminator if `@PacketType` is present on the same class.

Implicit "first FixedSize field is pre-prefix" was rejected as fragile
under field reordering.

### Q3 — Sealed-parent composition

`@FramedBy` goes on the **sealed parent only** (e.g., `MqttPacket`,
`MqttV5Packet`). Every variant inherits the framing rule. No per-variant
override mechanism — that was rejected as over-engineered (the override
solves a problem we don't have; every MQTT variant uses the same framing
shape).

Validator enforces: every variant of a `@FramedBy` parent must have the
named `after` field as a FixedSize discriminator.

### Q4 — Decode API

The decoded length value is **discarded**; the data class loses the
`remainingLength` field entirely. Decode is **strict**: after decoding
all body fields, assert `buffer.position() == bound`. Throw
`DecodeException` if the body under-consumes the bounded region:

```kotlin
return try {
    val body = decodeBodyFields(buffer, context)
    if (buffer.position() != __framingBound) {
        throw DecodeException(
            fieldPath = "${className}.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "body to consume $__framingLength bytes",
            actual = "${buffer.position() - __framingStart} bytes",
        )
    }
    constructWith(header, body)
} finally {
    buffer.setLimit(__framingOuterLimit)
}
```

### Q5 — Encode strategy: slicing scheme

The codec's encode signature **changes** for `@FramedBy` parents:

```kotlin
// Today on Codec<T>:
fun encode(buffer: WriteBuffer, value: T, context: EncodeContext)

// For @FramedBy parents, generated codec has:
fun encode(value: T, context: EncodeContext, factory: BufferFactory): ReadBuffer
```

Encode body:

```kotlin
val maxSlack = headerWidth + maxPrefixWidth      // 1 + 4 = 5 for MQTT
val growable = GrowableWriteBuffer(factory, initialSize = maxSlack + 256)
growable.position(maxSlack)                       // skip slack, body at offset 5
variant.encodeBodyOnly(growable, value, context)  // skips header field
val bodyBytes = growable.position() - maxSlack
val actualPrefixWidth = framingCodec.wireSize(bodyBytes.toUInt(), ctx).asExact
val sliceStart = maxSlack - actualPrefixWidth - headerWidth

val buffer = growable.toReadBuffer().asPlatformBuffer()
buffer.position(sliceStart)
writeHeaderField(buffer, value)                   // generated, fixed-width
framingCodec.encode(buffer, bodyBytes.toUInt(), context)
buffer.position(sliceStart)
buffer.setLimit(maxSlack + bodyBytes)
return buffer.slice()
```

The body never moves; header + prefix are written into the slack region
post-hoc. Caller gets a `ReadBuffer` slice — the unused slack at the
front of the underlying buffer never reaches the wire.

**Why slicing instead of reserve-and-shift:** zero memcpy of body bytes.
The reserve-and-shift alternative would shift the body left by 0–3 bytes
at the end of every encode; the slicing scheme leaves the body fixed and
right-flushes the prefix into the slack instead. Both are zero-copy of
the body content; slicing is structurally cleaner and matches the user's
explicit preference.

**Why deferred header write:** with `after = "header"`, the header byte
sits before the prefix on the wire. If the variant codec writes the
header at offset `maxSlack` (top of slack), closing the gap requires a
1-byte memmove. By deferring the header write to the framework (variant's
generated `encodeBodyOnly` skips the `after`-named field), zero memcpy
is achieved.

### Q6 — Migration

`@DerivedLength` is removed in the **same commit** as `@FramedBy` lands
(slice 14b). No deprecation period. The annotation, its validator
(`validateDerivedLength`), its emitter branch in
`appendEncodeUseCodecScalar`, and the slice 14a probe
(`Slice14aDerivedFrame.kt` + tests) all delete in slice 14b's commit.

### Q7 — Validator

Codec target restricted to `BoundingLengthCodec<UInt>` (structural).
Diagnostics:

| Code | Diagnostic |
|------|------------|
| E1 | codec target must implement `BoundingLengthCodec<UInt>` |
| E2 | `after = "X"` names a field not on the primary constructor; available: ... |
| E3 | `after = "X"` field type doesn't have Exact wire width |
| E4 | class has `@PacketType` but `after = ""`; discriminator must precede the prefix |
| E6 | class has both `@FramedBy` and `@DerivedLength` (transient — only fires if a stale fixture survives the same-commit removal) |

E5 (suffix-must-be-fixed) from the original sketch is **dropped**: the
slicing scheme handles fixed-suffix and BackPatch-suffix bodies
identically, so the case 1 / case 2 distinction collapses. Validator no
longer needs a suffix-shape check.

### Q8 — Slice split

Two slices:

- **Slice 14b — capability + remove `@DerivedLength`:**
  - `@FramedBy` annotation in `:buffer-codec`.
  - Validator E1-E4 + E6 in `:buffer-codec-processor`.
  - Analyzer/emitter for slicing scheme in `CodecEmitter.kt`.
  - `BoundingLengthCodec<UInt>` gains `val maxWireSize: Int`.
  - `MqttRemainingLengthCodec.maxWireSize = 4`.
  - `GrowableWriteBuffer` re-introduced as `internal class` in
    `:buffer-codec` (see Q9).
  - `encodeBodyOnly` emit on `@PacketType` variant codecs (skips the
    `after`-named field).
  - Generated `@FramedBy` parent codec returns `ReadBuffer` from encode.
  - New probe `Slice14bFramedFrame` (replaces `Slice14aDerivedFrame`).
  - `@DerivedLength` annotation + processor branches + slice 14a probe
    deleted in same commit.
  - Net test delta: ~0 (probe swap; +3 from new probe, -3 from old).

- **Slice 14c — v3/v5 substitution:**
  - `@FramedBy(MqttRemainingLengthCodec::class, after = "header")` on
    `MqttPacket` and `MqttV5Packet` sealed parents.
  - `remainingLength: UInt` removed from all 14 v3 variants.
  - `remainingLength: UInt` removed from all 14 v5 variants.
  - MQTT round-trip test sites updated to consume `ReadBuffer` return
    from the dispatcher's encode.
  - Net test delta: 0 (round-trips still pass; constructor-arg
    revisions only).

### Q9 — Buffer growth

Re-introduce `GrowableWriteBuffer` as `internal class` in
`:buffer-codec`. **Implementation precedent already exists:**

- Commit `2c28aa85` ("Replace sizeOf with GrowableWriteBuffer for safe,
  auto-sizing encodeToBuffer") — full source reachable via git history.
- Live file in `.claude/worktrees/agent-acbc960a6159a0113/buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/GrowableWriteBuffer.kt`
  (and sibling worktrees).

Behavior: thin `WriteBuffer` wrapper around a `PlatformBuffer`; doubles
capacity on overflow via `factory.allocate(newCapacity)` + `newBuffer.write(old)`
+ `old.freeNativeMemory()`. Slack at offset 0 is preserved across
reallocations because the doubling copy preserves bytes `[0, position)`.
Pooled / deterministic factories compose correctly (same factory used
for growth allocations).

**Phase 9's reset (commit `c03dac7c`) was about the broader strategy
(legacy/new emitter coexistence, validation gaps), not GrowableWriteBuffer's
design.** Re-introducing it in slice 14b is justified by the slicing
scheme's need; it does not re-open the Phase 9 strategic questions.

## Verification findings worth carrying

- `ReadBuffer.slice(byteOrder)` returns a new ReadBuffer that shares the
  underlying memory but has independent position/limit. Slice spans
  `[parent.position, parent.limit)`. To slice an arbitrary range, codec
  must set `parent.position(start)` + `parent.setLimit(end)` first, slice,
  then restore. Codec-internal bookkeeping; not exposed to callers.
  Doc: `buffer/src/commonMain/kotlin/com/ditchoom/buffer/ReadBuffer.kt:90-110`.
- All buffers in current branch have fixed `capacity: Int` set at
  allocation. There is no auto-grow facility outside of
  `GrowableWriteBuffer` (which is currently absent — see Q9).
- `BoundingLengthCodec<UInt>` interface (`buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/BoundingLengthCodec.kt`)
  needs a new `val maxWireSize: Int` member so analyzer can size the
  slack region at codegen time. `MqttRemainingLengthCodec.maxWireSize = 4`
  (per `MqttRemainingLengthCodec.wireSize` returning `Exact(1..4)` based
  on input value — the upper bound is 4).

## Implementation roadmap (slice 14b)

Files that need touching in slice 14b's commit, roughly:

**`:buffer-codec`:**
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/annotations/Annotations.kt`
  — add `@FramedBy`, remove `@DerivedLength`.
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/BoundingLengthCodec.kt`
  — add `val maxWireSize: Int`.
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/GrowableWriteBuffer.kt`
  — new file (port from worktree precedent).

**`:buffer-codec-processor`:**
- `src/main/kotlin/com/ditchoom/buffer/codec/processor/ProtocolMessageProcessor.kt`
  — add `validateFramedBy` (E1-E4 + E6), remove `validateDerivedLength`.
- `src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt`
  — add `@FramedBy` analyzer branch; emit slicing-scheme encode +
  strict decode + `encodeBodyOnly` on variants; remove `@DerivedLength`
  emitter branch in `appendEncodeUseCodecScalar`.

**`:buffer-codec-test`:**
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttRemainingLengthCodec.kt`
  — add `override val maxWireSize: Int = 4`.
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/slice14b/Slice14bFramedFrame.kt`
  — new probe fixture (3+ tests).
- `src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/slice14a/Slice14aDerivedFrame.kt`
  — delete.
- `src/commonTest/kotlin/com/ditchoom/buffer/codec/test/protocols/slice14a/Slice14aProbeCodecTest.kt`
  — delete.
- `src/commonTest/kotlin/com/ditchoom/buffer/codec/test/protocols/slice14b/Slice14bFramedFrameCodecTest.kt`
  — new (replaces deleted slice 14a tests).

**Validator tests (`:buffer-codec-processor`):**
- New positive cases for `@FramedBy` shapes.
- New negative cases for E1-E4 + E6.
- Removed: `@DerivedLength` validator tests (deleted with the annotation).

## Probe fixture shape

`Slice14bFramedFrame` should be the case-1-and-case-2-unified probe.
Suggested shape: a class with a fixed-size suffix (exercising the
zero-body-shift path) and a separate class with a `@LengthPrefixed
String` suffix (exercising the BackPatch-body path through the same
emit). Both go through the slicing scheme; the test demonstrates the
distinction collapsed.

```kotlin
@ProtocolMessage
@FramedBy(MqttRemainingLengthCodec::class)
data class Slice14bFramedFrameFixed(
    val payload: UByte,
    val tail: UShort,
)

@ProtocolMessage
@FramedBy(MqttRemainingLengthCodec::class)
data class Slice14bFramedFrameVariable(
    @LengthPrefixed val message: String,
)
```

For the `after = "header"` case, the v3/v5 substitution in slice 14c is
the natural exercise — a slice 14b probe with `after = "X"` would
duplicate that. Skip a probe for `after =`; let v3/v5 round-trips cover it.

## Prompt to start the next session

> **Resume Phase J.M.5 — implement slice 14b (`@FramedBy` capability +
> remove `@DerivedLength`).**
>
> Read in order: `PHASE_J_M_5_SLICE_14B_HANDOFF.md` (top-to-bottom), the
> slice 14a commit message (`git log 851332ca`), and the existing
> `@DerivedLength` validator + emitter branches that you'll be removing
> (`grep -rn "DerivedLength" buffer-codec buffer-codec-processor`).
>
> Confirm green baseline before implementing:
>
> ```
> ./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
> ./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck
> ```
>
> Expected `486 / 63 / 36`. ktlint clean.
>
> **Implementation scope** — slice 14b lands as one commit:
>
> 1. `@FramedBy` annotation in `:buffer-codec`.
> 2. `BoundingLengthCodec<UInt>.maxWireSize: Int` + override on
>    `MqttRemainingLengthCodec` (= 4).
> 3. `internal class GrowableWriteBuffer` re-introduced in `:buffer-codec`
>    — port from `.claude/worktrees/agent-acbc960a6159a0113/buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/GrowableWriteBuffer.kt`
>    or from commit `2c28aa85`.
> 4. Validator E1-E4 + E6 in `:buffer-codec-processor`.
> 5. Analyzer + emitter for the slicing-scheme encode +
>    strict-decode + `encodeBodyOnly` on variants.
> 6. New probe `Slice14bFramedFrame` (Fixed + Variable shapes, see handoff
>    "Probe fixture shape").
> 7. Remove: `@DerivedLength` annotation + validator + emitter branch +
>    slice 14a probe + slice 14a tests.
>
> Don't substitute v3/v5 fixtures in this slice — that's slice 14c.
>
> Each numbered item maps to a hunk; the commit should be reviewable as
> a clean replacement (not a rewrite). Test count target: ≈486 (probe
> swap, no production fixture impact).
>
> ktlint clean. Cross-target compile clean (linuxX64 / js / wasmJs).
>
> Once 14b lands, slice 14c is the v3/v5 substitution: drop
> `remainingLength` from 14 v3 + 14 v5 variants, add `@FramedBy` to both
> sealed parents, update test call sites for the `ReadBuffer` return
> contract.

## What this handoff is NOT

- Not a re-design. The 9 questions are answered; do not re-open them.
- Not a green light to skip the design's strict-decode requirement (Q4)
  for "simplicity." The strict check is structural — the bound exists
  because we own framing now, and an under-consuming body is a bug.
- Not authorization to keep `@DerivedLength` as a transitional path
  (Q6 ruled remove-in-same-commit). Two annotations for the same wire
  concern is the muddle the design pass exists to prevent.
- Not authorization to defer `GrowableWriteBuffer`'s re-introduction
  (Q9). The slicing scheme requires it; without it, the encode path
  has no growth strategy for variable bodies.
