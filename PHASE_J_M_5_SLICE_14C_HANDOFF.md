# Phase J.M.5 — slice 14c handoff (sealed-parent `@FramedBy` + MQTT v3/v5 substitution)

**Read this top-to-bottom before writing code.** Slice 14b landed the
standalone `@FramedBy` capability (probe-only). Slice 14c attaches the
annotation to the MQTT v3 and v5 sealed parents, drops `remainingLength`
from 28 variants, and updates test sites. There is prerequisite emitter
work (called **14c-prep** below) before the substitution is mechanical.

## Branch state at start of 14c

Slice 14b's diff is **uncommitted** in the working tree at HEAD
`851332ca` (still the slice 14a commit). Before starting 14c, verify the
14b diff is consistent with `PHASE_J_M_5_SLICE_14B_HANDOFF.md` and
either commit 14b or work on top of the staged tree.

Test baseline at end of 14b:

```
:buffer-codec-test:jvmTest    = 489
:buffer-codec-processor:test  = 68
:buffer-flow:jvmTest          = 36
```

ktlint clean. Cross-target compile clean (linuxX64 / js / wasmJs).

## What slice 14b shipped (already in the tree)

You don't need to redo any of this; reference it as you implement 14c.

- `@FramedBy(codec, after)` annotation in `:buffer-codec`
  (`Annotations.kt`).
- `BoundingLengthCodec.maxWireSize: Int` interface member; overrides
  `= 4` on `MqttRemainingLengthCodec` and `Le32LengthCodec`.
- `internal class GrowableWriteBuffer` (`:buffer-codec`,
  `GrowableWriteBuffer.kt`) — auto-grows, preserves slack at offset 0
  across reallocations.
- `public object FramedEncoder` (`:buffer-codec`, `FramedEncoder.kt`)
  with the runtime entry point:
  ```kotlin
  FramedEncoder.encode(
      factory: BufferFactory,
      framingCodec: BoundingLengthCodec<UInt>,
      context: EncodeContext,
      headerWireWidth: Int = 0,
      writeHeader: ((PlatformBuffer) -> Unit)? = null,
      initialBodyEstimate: Int = 256,
      writeBody: (WriteBuffer) -> Unit,
  ): ReadBuffer
  ```
  **`headerWireWidth` and `writeHeader` are already implemented**,
  including the `sliceStart = maxSlack - actualPrefixWidth - headerWireWidth`
  arithmetic that right-flushes header-then-prefix into the slack region.
  14c-prep wires the emit to call them; the runtime side is done.
- Validator `validateFramedBy` covering E1-E4 + E6 in
  `ProtocolMessageProcessor.kt`. **Walks sealed-parent variants** for
  E2/E3 (after-field exists with Exact wire width on every variant's
  primary constructor) and E4 (after = "" rejected when any variant
  carries `@PacketType`).
- Standalone-shape emitter in `CodecEmitter.kt` — `analyze()` populates
  `CodecShape.framedBy: FramedByConfig?` via `detectFramedBy(symbol)`
  (currently only checks the symbol's own annotation, not inherited),
  and `buildFileSpec` routes to `buildFramedByFileSpec` when present.
  `buildFramedByDecodeFun` and `buildFramedByEncodeFun` implement only
  the `after = ""` case today.
- `Slice14bFramedFrame` probe (Fixed + Variable shapes) — six
  round-trip / wire-format / strict-bound tests, all green. Demonstrates
  the case-1/case-2 collapse the design pass promised.
- `@DerivedLength` annotation, validator, emitter branch, slice 14a
  probe + tests **already removed** in the same diff (per Q6 of the 14b
  handoff).
- 5 `FramedByValidatorTest` cases (1 positive + 4 negative for E1-E4).

## Slice 14c-prep — emitter changes (do these first)

These are the prerequisites for the MQTT substitution. Land them as one
change, verified with an extended sealed-parent probe (see "Probe for
14c-prep" below) before moving to 14c proper.

### Q1 — Inherited `@FramedBy` detection

Current `detectFramedBy(symbol)` in `CodecEmitter.kt` only reads the
symbol's own annotations. Per Q3 of the 14b handoff, every variant of a
`@FramedBy` parent inherits the framing rule. Extend `detectFramedBy`:

```kotlin
private fun detectFramedBy(symbol: KSClassDeclaration): FramedByConfig? {
    // Direct annotation on this symbol.
    symbol.annotations.firstOrNull(::isFramedByAnn)?.let { return parseFramedBy(it) }
    // Inherited from a sealed parent (Q3 — sealed-parent composition).
    for (superType in symbol.superTypes) {
        val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: continue
        if (Modifier.SEALED !in superDecl.modifiers) continue
        if (superDecl.classKind != ClassKind.INTERFACE) continue
        val ann = superDecl.annotations.firstOrNull(::isFramedByAnn) ?: continue
        return parseFramedBy(ann)
    }
    return null
}
```

This makes every `@PacketType` data class variant of a `@FramedBy`
sealed parent emit through the `@FramedBy` file spec.

### Q2 — `after = "X"` decode emit

Current `buildFramedByDecodeFun` reads the prefix first, then all body
fields, then asserts strict bound. For `after = "X"`, X comes BEFORE the
prefix on the wire. New decode order:

1. Read X (the after-field).
2. Read prefix → `applyBound` → capture outer limit.
3. Read remaining body fields (skipping X — already read).
4. Strict bound check.
5. Construct + restore outer limit in `finally`.

Code sketch:

```kotlin
val afterField = framedBy.afterFieldName.takeIf { it.isNotEmpty() }
    ?.let { name -> shape.fields.firstOrNull { it.name == name } }
if (afterField != null) {
    appendDecodeField(body, afterField)  // reads X, declares `val X = ...`
}
body.addStatement("val __framingOuterLimit = buffer.limit()")
body.addStatement("val __framingLength = %T.decode(buffer, context)", framedBy.codecClassName)
body.addStatement("%T.applyBound(buffer, __framingLength)", framedBy.codecClassName)
// ... rest unchanged, but the body-field loop skips afterField:
for (field in shape.fields) {
    if (field === afterField) continue
    appendDecodeField(body, field)
}
```

The constructor invocation already references all fields by name
(`ctorArgs` joins `${it.name} = ${it.name}`), so the after-field's local
binds correctly into the data class.

### Q3 — `after = "X"` encode emit

Current `buildFramedByEncodeFun` calls
`FramedEncoder.encode(factory, codec, context) { buffer -> /* body */ }`.
For `after = "X"`, pass `headerWireWidth` and `writeHeader`:

1. Compute `headerWireWidth: Int` at codegen time from afterField's wire
   shape:
   - For `FieldSpec.Scalar` with `kind` in NATURAL_WIDTHS: use `field.wireBytes`.
   - For `FieldSpec.ValueClassScalar`: use the inner-scalar's natural
     width (mirror of the validator's E3 acceptance set).
   - Other field shapes are forbidden by E3 — emit a defensive `error(...)`.
2. Skip afterField from the body-encode loop.
3. Emit a `writeHeader = { buffer -> /* afterField encode */ }` lambda
   that calls `appendEncodeField` (the existing dispatch) with `buffer`
   in scope. The lambda's `buffer` parameter is `PlatformBuffer`; the
   existing scalar/value-class encode emit uses `buffer.writeXyz(...)`
   which is part of the `WriteBuffer` interface that `PlatformBuffer`
   extends, so it works unchanged.

Code sketch:

```kotlin
val afterField = framedBy.afterFieldName.takeIf { it.isNotEmpty() }
    ?.let { name -> shape.fields.firstOrNull { it.name == name } }
val headerWireWidth = afterField?.let(::computeHeaderWireWidth) ?: 0

body.add("return %T.encode(\n", FRAMED_ENCODER_CN)
body.indent()
body.add("factory = factory,\n")
body.add("framingCodec = %T,\n", framedBy.codecClassName)
body.add("context = context,\n")
if (afterField != null) {
    body.add("headerWireWidth = %L,\n", headerWireWidth)
    body.add("writeHeader = { buffer ->\n")
    body.indent()
    appendEncodeField(body, afterField, shape.ownerSimpleName)
    body.unindent()
    body.add("},\n")
}
body.unindent()
body.beginControlFlow(") { buffer ->")
for (field in shape.fields) {
    if (field === afterField) continue
    appendEncodeField(body, field, shape.ownerSimpleName)
}
body.endControlFlow()
```

You'll want a small helper `appendEncodeField(body, field, ownerSimpleName)`
that wraps the existing `when (field) { ... }` dispatch from
`buildEncodeFun`, since you'll call it from two places (the body loop
and the `writeHeader` lambda).

### Q4 — `peekFrameSize` for `@FramedBy` variants

Slice 14b's `buildFramedByFileSpec` does NOT emit `peekFrameSize`. For
14c, MQTT round-trip tests likely lean on `peekFrameSize` to size frames
on the wire before decode (per `CLAUDE.md` "peekFrameSize — Generated
Stream Framing"). Add a `peekFrameSize` for `@FramedBy` shapes:

```
peekFrameSize(stream, baseOffset = 0): Int? =
    headerWireWidth +
    framingCodec.peekFrameSize(stream, baseOffset + headerWireWidth) +
    framingCodec.lastDecodedValue   // i.e. the prefix's bound
```

The cleanest way: `MqttRemainingLengthCodec` already has the wire
shape needed (variable-byte int with continuation bit), so the peek is
"read header + read prefix → return header + prefix-width + prefix-value".
Generate this as part of `buildFramedByFileSpec`.

If a sealed parent has `@FramedBy`, the dispatcher's `peekFrameSize`
delegates: `peekFrameSize(stream)` reads the header (1 byte for MQTT),
then reads the prefix VBI to determine total frame size. That's
identical for every variant, so it lives on the dispatcher, not
per-variant.

### Q5 — Sealed dispatcher `@FramedBy` integration

`buildDispatchOnDispatcherFileSpec` (the `@DispatchOn` path —
`MqttPacket` and `MqttV5Packet` go through here) needs:

1. **Detect parent `@FramedBy`** in `analyzeDispatchOnSealedDispatcher`,
   capture into `DispatchOnDispatcherShape.framedBy: FramedByConfig?`.
2. **Change encode signature** when `framedBy != null`:
   - From: `fun encode(buffer: WriteBuffer, value: Parent, context: EncodeContext)`
   - To:   `fun encode(value: Parent, context: EncodeContext, factory: BufferFactory): ReadBuffer`
   - Body: `when (value) { is V1 -> V1Codec.encode(value, context, factory); ... }`
   - **Drop the `Codec<Parent>` superinterface** when framing is owned —
     the dispatcher's encode contract no longer matches `Codec<T>`.
     Same as the standalone emit does today.
3. **Decode is unchanged in shape** — the dispatcher reads the
   discriminator (`MqttFixedHeader`), peeks back, dispatches to variant
   codec. Each variant codec (now emitted with `@FramedBy` because of
   Q1's inheritance detection) handles its own framing.
4. **`peekFrameSize` lives on the dispatcher** per Q4.
5. **`wireSize` becomes BackPatch or is dropped** for `@FramedBy`
   dispatchers — variable-length prefix means the size isn't computable
   without encoding. The slicing-scheme path doesn't use `wireSize`
   anyway (the encode returns a sized slice). Skip the
   `buildDispatchOnWireSizeFun` call when framedBy is set.

The non-`@DispatchOn` `buildSealedDispatcherFileSpec` path is unused by
MQTT (MQTT uses `@DispatchOn(MqttFixedHeader::class)`). Skip 14c work on
that path; add it later if a non-@DispatchOn protocol needs framing.

### Probe for 14c-prep

Before touching MQTT, write a sealed-parent probe that exercises the
`after = "X"` path. Use the existing `MqttRemainingLengthCodec` and a
1-byte header:

```kotlin
@JvmInline
@ProtocolMessage
value class Slice14cTinyHeader(val raw: UByte) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()
}

@DispatchOn(Slice14cTinyHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface Slice14cFramedDispatch {
    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
    data class A(val header: Slice14cTinyHeader, val a: UByte, val b: UShort) : Slice14cFramedDispatch

    @ProtocolMessage @PacketType(value = 2, wire = 0x20)
    data class B(val header: Slice14cTinyHeader, @LengthPrefixed val message: String) : Slice14cFramedDispatch
}
```

Expected wire for `A(header = 0x10, a = 0x42, b = 0xABCD)`:
`10 03 42 AB CD` (header `10`, prefix `03` for 3 body bytes, body).

Expected wire for `B(header = 0x20, message = "hi")`:
`20 04 00 02 68 69` (header `20`, prefix `04` for 2-byte length prefix +
2 body bytes, body).

Tests:
- A round-trip
- B round-trip (1-byte VBI prefix)
- B round-trip with `message = "x".repeat(200)` (forces 2-byte VBI prefix)
- Dispatch correctness (encode A → decode returns A, not B)
- Strict bound rejection (under-consumed body throws)

Lives in `:buffer-codec-test/src/commonMain/.../slice14c/Slice14cFramedDispatch.kt`
and `:buffer-codec-test/src/commonTest/.../slice14c/Slice14cFramedDispatchCodecTest.kt`.

Net test delta for 14c-prep: +5 (the new probe).

## Slice 14c — MQTT v3/v5 substitution (after 14c-prep is green)

### Files to touch

**`:buffer-codec-test/src/commonMain/.../mqtt/MqttPacket.kt`** (sealed
parent + 14 v3 variants):
- Add `@FramedBy(MqttRemainingLengthCodec::class, after = "header")` to
  the `sealed interface MqttPacket` declaration.
- Drop `val remainingLength: UInt` from each of the 14 variants'
  primary constructors.
- Drop any default values, computations, or callers that supply
  `remainingLength`.

**`:buffer-codec-test/src/commonMain/.../mqttv5/MqttV5Packet.kt`** (sealed
parent + 14 v5 variants):
- Same as v3. Variants share the constructor pattern; the `header:
  MqttFixedHeader` field stays, the `remainingLength: UInt` field goes.

### Test call sites

23 test files reference `remainingLength` per `grep -rn remainingLength
buffer-codec-test/src/commonTest --include="*.kt" | wc -l`. Two changes
to make in each:

1. **Constructor calls** — drop the `remainingLength = …` argument.
   (Mostly mechanical. Some tests construct packets with computed
   remaining lengths — those computations become dead and should be
   deleted.)
2. **Encode call sites** — switch from
   `MqttPacketCodec.encode(buffer, packet, context)` to
   `val read = MqttPacketCodec.encode(packet, context, factory)` and
   read the wire bytes from `read` instead of the caller-supplied
   buffer. Tests that pre-allocate a fixed-size buffer can drop the
   allocation entirely.

There are also non-test sites that call `Mqtt(V5)?PacketCodec.encode`
— check `:buffer-flow` and the round-trip test fixtures. The encode
contract change ripples to every caller.

### Round-trip invariant

Per the 14b handoff Q8: "Net test delta: 0 (round-trips still pass;
constructor-arg revisions only)." Verify by running the same MQTT test
counts before and after substitution. The wire bytes don't change —
only the data class shape and the encode call signature.

### Possible breakage points to watch

- **`peekFrameSize` consumers.** If any test or `:buffer-flow` site
  calls `MqttPacketCodec.peekFrameSize(stream)`, confirm 14c-prep Q4's
  emit on the dispatcher matches today's contract (returns `Int?`, same
  arithmetic).
- **`Codec<MqttPacket>` consumers.** Anything that passes
  `MqttPacketCodec` as a `Codec<MqttPacket>` parameter breaks because
  the dispatcher no longer extends `Codec`. Check `:buffer-flow`'s
  `Sender`/`Receiver` integration — those probably want the new
  `(value, context, factory): ReadBuffer` shape adapted via a thin
  wrapper.
- **Encode partial / aggregator paths.** The dispatcher emits
  `Partial<P>` and `decodeAggregating(...)` — both touch decode only,
  unaffected. But verify by reading
  `MqttPacketAggregatorCodecTest.kt`.

### Net test delta

Per the 14b handoff: 0. The MQTT round-trip tests still pass; the new
sealed-parent probe from 14c-prep adds tests but is its own slice.

## Verification

```bash
# 14c-prep
./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
# Expected: 489 + 5 (probe) = 494 / 68 / 36

./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck

# Cross-target
./gradlew :buffer-codec-test:compileKotlinLinuxX64 \
          :buffer-codec-test:compileKotlinJs \
          :buffer-codec-test:compileKotlinWasmJs

# 14c (post-substitution)
./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
# Expected: 494 / 68 / 36 (unchanged from 14c-prep)
```

## Prompt to start the next session

> **Resume Phase J.M.5 — implement slice 14c-prep, then slice 14c (MQTT
> v3/v5 substitution).**
>
> Read in order: `PHASE_J_M_5_SLICE_14C_HANDOFF.md` (top-to-bottom), the
> slice 14b handoff (`PHASE_J_M_5_SLICE_14B_HANDOFF.md`) for context,
> and the existing standalone `@FramedBy` emit in
> `buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt`
> (search for `buildFramedByFileSpec`).
>
> Confirm green baseline before implementing:
>
> ```
> ./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
> ./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck
> ```
>
> Expected `489 / 68 / 36`. ktlint clean.
>
> **14c-prep scope** — land as one commit:
>
> 1. Inherited `@FramedBy` detection in `detectFramedBy(symbol)` (Q1).
> 2. `after = "X"` support in `buildFramedByDecodeFun` (Q2).
> 3. `after = "X"` support in `buildFramedByEncodeFun` + a small
>    `appendEncodeField` helper (Q3).
> 4. `peekFrameSize` emit for `@FramedBy` shapes (Q4).
> 5. Sealed dispatcher `@FramedBy` integration in
>    `buildDispatchOnDispatcherFileSpec` (Q5).
> 6. New probe `Slice14cFramedDispatch` (A + B variants, see "Probe for
>    14c-prep" above) + 5 tests.
>
> Verify: tests `494 / 68 / 36`, ktlint clean, cross-target compile clean.
>
> **14c scope** — land as a follow-up commit once 14c-prep is green:
>
> 1. `@FramedBy(MqttRemainingLengthCodec::class, after = "header")` on
>    `MqttPacket` and `MqttV5Packet` sealed parents.
> 2. Drop `remainingLength: UInt` from all 14 v3 + 14 v5 variants.
> 3. Update test sites: drop `remainingLength = ...` constructor args,
>    switch encode call sites to `(value, context, factory): ReadBuffer`
>    return.
> 4. Update `:buffer-flow` integration sites if any pass
>    `MqttPacketCodec` as a `Codec<MqttPacket>` parameter — see "Possible
>    breakage points" in this handoff.
>
> Verify: tests `494 / 68 / 36` (unchanged from 14c-prep), ktlint clean,
> cross-target compile clean.

## What this handoff is NOT

- Not a redesign. The 9 questions from the 14b handoff are answered;
  this handoff inherits them and only adds emitter prep + substitution
  detail.
- Not a green light to skip the 14c-prep probe. The MQTT substitution
  must NOT be the first time `after = "header"` exercises the emit —
  the sealed-parent probe is the focused test that catches emit bugs
  before they hit 28 production fixtures.
- Not authorization to keep `Codec<Parent>` as a superinterface on the
  `@FramedBy` dispatcher (Q5.2). The encode signature is
  fundamentally different; pretending the dispatcher implements `Codec`
  would force a throwing stub `encode(buffer, value, context)` that's
  worse than the explicit signature mismatch.
- Not authorization to defer `peekFrameSize` to a later slice (Q4).
  MQTT round-trip tests rely on it for stream framing; without it, the
  v3/v5 substitution leaves a regression.
