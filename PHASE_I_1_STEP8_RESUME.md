# Phase I.1 Resume Briefing — steps 7–8 landed, step 9 next

This document briefs the next session on the Phase I.1 work landed
through step 8 on `feature/directional-codec`. Read
`PHASE_I_1_RESUME.md` first (the steps-1–6 briefing) — it has the
overall context. Read `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md` for the
locked design spec. This file is the incremental delta after the
step-7 fixture and the step-8 PingReq smoke-test swap.

## Branch state (as of this writing)

- Branch: `feature/directional-codec`.
- HEAD: `d1e0fae0` (step 6) plus **two unstaged additions** for step 7
  and **one unstaged edit** for step 8:
  - `buffer-codec-test/src/commonMain/.../mqtt/MqttRemainingLengthCodec.kt`
    (new — step 7 fixture).
  - `buffer-codec-test/src/commonTest/.../mqtt/MqttRemainingLengthCodecTest.kt`
    (new — 17 tests).
  - `buffer-codec-test/src/commonMain/.../mqtt/MqttPacket.kt`
    (modified — `PingReq.remainingLength` annotation swap +
    `UseCodec` import).
- These should land as two commits before step 9 begins:
  1. `Phase I.1 step 7: MqttRemainingLengthCodec fixture + tests`
  2. `Phase I.1 step 8: migrate PingReq to @UseCodec(MqttRemainingLengthCodec)`
- Test counts at the staged HEAD:
  - `:buffer-codec-test:jvmTest` — **232** (215 baseline + 17 new
    `MqttRemainingLengthCodecTest`).
  - `:buffer-codec-processor:test` — **53** (unchanged).
  - `:buffer-flow:jvmTest` — **36** (unchanged).
- Cross-target compile (`compileKotlinJs`, `compileKotlinWasmJs`,
  `compileKotlinLinuxX64`) clean for `:buffer-codec-test`.
- ktlint clean on commonMain + commonTest.

## What step 7 landed

`MqttRemainingLengthCodec` is a `BoundingLengthCodec<UInt>` reference
fixture in the MQTT package. Verbatim from the design spec lines
167–225. Implements `decode` (LSB-first 7-bit + continuation, max 4
bytes, throws `DecodeException` with `fieldPath = "MqttRemainingLength"`
on a 5th continuation byte), `encode` (mirror with `require(value <=
0x0FFF_FFFF)`), `wireSize` (returns `WireSize.Exact(1..4)` based on
value), and `applyBound` (`buffer.setLimit(buffer.position() +
decodedValue.toInt())`).

The fixture lives **with the MQTT fixtures**, not in `:buffer-codec`
— per design doc explicit decision: no `:buffer-codec-stdlib`.

`MqttRemainingLengthCodecTest` covers:
- Byte-exact encode at every byte-width transition (0, 127, 128,
  16_383, 16_384, 0x0FFF_FFFF).
- Byte-exact decode at the same boundaries.
- Round-trip across 8 boundary values including 2_097_151/2_097_152.
- Malformed 5-byte input throws `DecodeException` with
  `fieldPath = "MqttRemainingLength"`.
- Encode rejects `0x1000_0000u` (> max) with `IllegalArgumentException`.
- `wireSize` reports correct width per byte-count transition.
- `applyBound` narrows limit to `position + value` without moving
  position.

## What step 8 landed

One annotation swap in `MqttPacket.PingReq`:

```kotlin
// before:
@RemainingLength val remainingLength: UInt = 0u
// after:
@UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u
```

`UseCodec` import added; `RemainingLength` import retained (4 other
variants — `Connect`, `Publish`, `PingResp`, `Disconnect` — still use
it).

Validates the entire steps-1–6 chain end-to-end:
1. **Step 3 validator** accepts bare `@UseCodec val: UInt` on a
   non-`Payload` field.
2. **Step 4 emitter** detects `MqttRemainingLengthCodec` implements
   `BoundingLengthCodec` via supertype chain walk, emits
   `__remainingLengthOuterLimit` capture + `applyBound` call +
   try/finally.
3. **Step 5 peek-budget table** produces `5` for the `UInt` type
   (codec uses 1–4 in practice).
4. **Step 6 peek walker** materializes `stream.peekBuffer(1, 5)`,
   runs `MqttRemainingLengthCodec.decode` against the view,
   measures position delta, computes `Complete(2)` for PingReq's
   `C0 00` wire form.

`MqttPacketCodecTest` PingReq tests stayed green byte-for-byte:
- `encodesPingReqAsTwoBytes` — `C0 00`.
- `decodesPingReqFromSpecBytes` — round-trip.
- `roundTripsPingReqAndPingResp` — round-trip pair.
- `peekFrameSizeForPingReqCompletesAtTwoBytes` — `Complete(2)`.

If step 8 had broken, the bug would have been in steps 1–6. It
didn't. The chain is correct.

## Step 9 — Migrate the remaining variants (the hard one)

5 variants + the deferred slice 10f Partial outer-limit-capture
generalization. Approach the variants in two batches.

### Batch 9a — trivial swaps (no Partial change required)

These four are 1-line annotation swaps that should "just work" once
the import is added:

| File | Class | Field |
|------|-------|-------|
| `MqttPacket.kt` | `MqttPacket.Connect` | `remainingLength: UInt` (line 92) |
| `MqttPacket.kt` | `MqttPacket.PingResp` | `remainingLength: UInt = 0u` (line 165) |
| `MqttPacket.kt` | `MqttPacket.Disconnect` | `remainingLength: UInt = 0u` (line 176) |
| `MqttSubAck.kt` | `MqttSubAck` | `remainingLength: UInt` (line 38) |
| `MqttConnect.kt` | `MqttConnect` | `remainingLength: UInt` (line 93) |

After the swap, the `RemainingLength` import on each file is
removable (none should retain a `@RemainingLength` reference).

Run `:buffer-codec-test:jvmTest` after each swap. Test counts must
stay 232 and zero failures. The `MqttConnectCodecTest`,
`MqttSubAckCodecTest`, and `MqttPacketCodecTest` are the regression
nets.

### Batch 9b — `MqttPacket.Publish` (the deferred slice 10f extension)

`Publish` combines a bounding-`UseCodecScalar` (`remainingLength`)
with a `RemainingBytesPayload` (`payload: P`). This is the case the
step-1–6 work explicitly **deferred** in step 4 because no fixture
required it yet.

The Partial machinery in `CodecEmitter.kt` currently gates the
outer-limit-capture machinery on `FieldSpec.RemainingLength`. Need
to generalize to "any bounding field" (covers both `RemainingLength`
and `UseCodecScalar(isBounding = true)`). The `isBoundingShape()`
helper (line 3261) already exists and handles both.

Three call sites in `CodecEmitter.kt` (~1395 area) need lifting:

#### 1. `buildPartialClassTypeSpec` — `hasRemainingLength` (line 1499)

```kotlin
// current (line 1499):
val hasRemainingLength = shape.fields.any { it is FieldSpec.RemainingLength }

// generalize to:
val hasBoundingField = shape.fields.any { it.isBoundingShape() }
```

Then propagate the rename through the function body. The `outerLimit`
parameter (line 1520) and private property (line 1530) are already
generic — their literal name is `"outerLimit"`, no rename needed
there.

#### 2. `buildPartialCompleteFun` parameter (line 1570)

```kotlin
// current:
hasRemainingLength: Boolean,
// rename to:
hasBoundingField: Boolean,
```

The `try { ... } finally { buffer.setLimit(outerLimit) }` body (lines
1611–1621) reads the property by name `outerLimit`, so no body
change.

#### 3. `buildPartialEntryFun` — `rlField`/`outerLimitArgs` (lines 1671–1680)

This is the load-bearing change. Today:

```kotlin
val rlField = shape.fields.firstOrNull { it is FieldSpec.RemainingLength } as? FieldSpec.RemainingLength
val outerLimitArgs =
    if (rlField != null) {
        listOf("outerLimit = __${rlField.name}OuterLimit")
    } else {
        emptyList()
    }
```

Generalize to find any bounding field (using `FieldSpec.name` which
both variants expose):

```kotlin
val boundingField = shape.fields.firstOrNull { it.isBoundingShape() }
val outerLimitArgs =
    if (boundingField != null) {
        listOf("outerLimit = __${boundingField.name}OuterLimit")
    } else {
        emptyList()
    }
```

The local-name convention `__<fieldName>OuterLimit` is already
emitted identically by both `appendDecodeRemainingLength` (slice 8)
and `appendDecodeUseCodecScalar` (Phase I.1 step 4). Both write to
that exact local before the field's decode runs. So the partial
constructor's `__<name>OuterLimit` reference resolves correctly for
either field type without any emit-side change.

#### Wider hunt for `@RemainingLength`-only gates

Run `grep -n "is FieldSpec\.RemainingLength" buffer-codec-processor`
once batch 9a is done. There are several `when` branches and
firstOrNull filters across `CodecEmitter.kt` (lines 1402, 1423, 1671
[fixed above], 1733, 1758, 1762, 1869, 1909, 1934 etc.). Most of
these are emit branches that need to STAY targeted at
`RemainingLength` — they're the slice 8 paths that emit the var-int
decode. Step 10 will delete them after step 9 ensures no fixture
relies on them.

Specifically: lines 1402 + 1423 (decode/encode dispatch) — STAY
until step 10. Lines 1733 (typeNameForFieldSpec → `U_INT`) — STAY
until step 10. Lines 1758 + 1762 (`wireSize` short-circuit on
RemainingLength) — STAY. Lines 1869 (assertion message), 1909
(`appendPeekRemainingLength` dispatch), 1934 (UseCodecScalar peek
dispatch) — these are the slice 8 peek branches; the new
`isBoundingShape()` peek path emits via `appendPeekUseCodecScalar`,
already handled by step 6.

The gate that needs to move is `hasRemainingLength` (Partial outer-
limit capture), and only that one.

#### Validator diagnostic generalization

After step 9 lands, audit `validateUseCodec` and any `shouldEmitPartial`
companion code in `ProtocolMessageProcessor.kt`/`CodecEmitter.kt` for
diagnostic strings that explicitly say "RemainingLength" — generalize
to "bounding length codec" or similar. Diagnostic regression tests
in `:buffer-codec-processor:test` (`UseCodecScalarValidatorTest`)
are the net.

### Step 9 acceptance

Run all of:
1. `./gradlew :buffer-codec-test:jvmTest` — 232 tests, zero failures,
   no `@RemainingLength` reference in any fixture
   (`grep -rn "@RemainingLength" buffer-codec-test/src/` returns
   nothing).
2. `./gradlew :buffer-codec-processor:test` — 53 tests, zero failures.
3. `./gradlew :buffer-flow:jvmTest` — 36 tests including
   `CodecConnectionSmokeTest.aggregatorPathTopicKeyed` (the PUBLISH
   end-to-end path through the slice 10d.5 aggregator + slice 10c
   Partial). This is the load-bearing test that exercises the
   migrated `Publish` fixture through the `Connection<MqttPacket<TextPayload>>`
   boundary.
4. Cross-target compile clean.
5. ktlint clean.

If `aggregatorPathTopicKeyed` fails, the bug is in batch 9b's
`buildPartialEntryFun` lift — the Partial likely isn't capturing
`__remainingLengthOuterLimit` for the `Publish` shape. Inspect the
generated `MqttPacketCodecPublishCodec.partial` body in
`buffer-codec-test/build/generated/ksp/jvm/jvmMain/kotlin/...` and
compare to the previous-emit version (steps 1–6 PingReq output is the
reference shape).

## Step 10 — `@RemainingLength` deletion (still after step 9)

Unchanged from `PHASE_I_1_RESUME.md` lines 326–369. After step 9
lands, no fixture references `@RemainingLength`; step 10 removes the
annotation, `FieldSpec.RemainingLength`, the emit branches
(`appendDecodeRemainingLength`, `appendEncodeRemainingLength`,
`appendPeekRemainingLength`), the `analyzeField` `remainingLengthAnn`
branch (line 344), the validator's RemainingLength handling, and the
`is FieldSpec.RemainingLength ->` exhaustive `when` branches the
compiler will surface.

The slice 10c `shouldEmitPartial` carve-out (line 1458) currently
gates on `RemainingBytesPayload` — re-read after step 9 lands to
confirm it doesn't gain a `RemainingLength` reference during the
generalization.

Test counts after step 10:
- `:buffer-codec-test:jvmTest` — 232 (unchanged; migrations are
  byte-exact).
- `:buffer-codec-processor:test` — 53 minus N where N = count of
  `@RemainingLength`-specific diagnostics tests.
- `:buffer-flow:jvmTest` — 36 (unchanged).
- `:buffer:jvmTest` — 1008 (unchanged).

## Step 11 — `@LengthPrefixed @UseCodec` composition

Optional within Phase I.1 per `PHASE_I_1_RESUME.md` line 372. Defer
if step 9 + step 10 grow scope.

## How to start the next session

1. Read `PHASE_I_1_RESUME.md` (steps 1–6 briefing).
2. Read this file (`PHASE_I_1_STEP8_RESUME.md`).
3. Read `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md` for the locked design.
4. Verify branch state. The two commits (step 7 fixture; step 8
   PingReq swap) should already be staged — confirm via `git log`.
5. Run the three test suites + cross-target compile to confirm green
   baseline at 232/53/36.
6. Start with batch 9a (4 trivial swaps in `MqttPacket.kt` for
   Connect/PingResp/Disconnect, plus `MqttSubAck.kt` and
   `MqttConnect.kt`). Run tests after each file.
7. Proceed to batch 9b (Publish + Partial outer-limit-capture lift in
   `CodecEmitter.kt`). The acceptance gate is
   `aggregatorPathTopicKeyed` in `:buffer-flow`.
8. Step 10 (annotation deletion) only after step 9 acceptance.

The 232 + 53 + 36 + 1008 test baseline must stay green throughout.
