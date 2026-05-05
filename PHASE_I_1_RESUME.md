# Phase I.1 Resume Briefing — steps 1–6 landed, steps 7–10 next

This document briefs the next session on the work landed for Phase I.1
(pluggable `@UseCodec`-driven length encoding) on
`feature/directional-codec`. Read `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md`
first — that's the locked design spec. Read `STAGE_H_RESUME.md` for the
broader emitter context. This file is the incremental delta after
six commits + the open work for steps 7–10.

## Branch state

- Branch: `feature/directional-codec`, head `d1e0fae0` (Phase I.1
  step 6).
- 6 stacked Phase I.1 commits past the design-doc commit
  (`5758020e`).
- All commits individually buildable + green at landing time.
- Test counts at HEAD:
  - `:buffer:jvmTest` — 1008 (+11 since pre-Phase-I.1, all
    `StreamProcessorPeekBufferTests`).
  - `:buffer-codec-test:jvmTest` — 215 (+11 net since
    pre-Phase-I.1, all `usecodecscalar` package).
  - `:buffer-codec-processor:test` — 53 (+7,
    `UseCodecScalarValidatorTest`).
  - `:buffer-flow:jvmTest` — 36 (unchanged; still 4
    `CodecConnectionSmokeTest`).
- Cross-target compile (`compileKotlinJs`, `compileKotlinWasmJs`,
  `compileKotlinLinuxX64`) clean for `:buffer-codec-test`.

## Commit log since the design doc

| Commit | Step | Capability |
|--------|------|------------|
| `b5e37564` | 1 | `StreamProcessor.peekBuffer(offset, maxBytes): ReadBuffer?` non-consuming view |
| `623fcdd2` | 2 | `BoundingLengthCodec<T> : Codec<T>` sub-interface (`applyBound` method) |
| `25b96c0d` | 3 | Validator accepts bare `@UseCodec val: <scalar>` |
| `9ab03926` | 4 | `FieldSpec.UseCodecScalar` + emit decode/encode + `applyBound` + try/finally |
| `94c06bf4` | 5 | Per-field-type peek-budget table |
| `d1e0fae0` | 6 | Generic `@UseCodec` peek walker (`peekBuffer` + `codec.decode` + position-delta) |

## Annotation surface as it stands today

Unchanged from STAGE_H_RESUME except:

- `@UseCodec(KClass<*>)` — now also valid as a bare annotation on
  scalar / value-class scalar fields (Phase I.1 step 3). The
  validator continues to reject `@LengthFrom @UseCodec` and
  `@LengthPrefixed @UseCodec` as deferred shapes.
- New: `BoundingLengthCodec<T> : Codec<T>` in
  `:buffer-codec/.../codec/`. Codec authors implementing this
  sub-interface signal that the decoded value should narrow
  `buffer.limit()` for the subsequent decode region.
- New: `StreamProcessor.peekBuffer(offset, maxBytes): ReadBuffer?`
  in `:buffer/.../stream/`. Caller releases the returned buffer via
  `freeNativeMemory()` (same contract as `readBuffer`).

## FieldSpec hierarchy as it stands today

Unchanged from STAGE_H_RESUME plus one new member:

- `FieldSpec.UseCodecScalar(name, ownerSimpleName, fieldType: TypeName,
  codecType: ClassName, isBounding: Boolean)` — Phase I.1.

`isBounding` is set when the codec target's KSP supertype chain
contains `BoundingLengthCodec` (`KSClassDeclaration.implementsBoundingLengthCodec()`
walks `getAllSuperTypes()`). When true, decode emits the outer-limit
capture + `applyBound` + try/finally outer-limit-restore.

The shape uniqueness check ("at most one `RemainingLength`")
generalized to "at most one bounding field" via
`FieldSpec.isBoundingShape()` — covers both `RemainingLength` and
`UseCodecScalar(isBounding = true)`. Multiple `setLimit` calls per
message would have ambiguous semantics; no real protocol has that
shape.

## Generated emit shape

For a bare `@UseCodec val: <T>` field (non-bounding):

```kotlin
// decode
val name = UserCodec.decode(buffer, context)

// encode
UserCodec.encode(buffer, value.name, context)

// wireSize
return WireSize.BackPatch  // conservative; runtime-Exact promotion deferred

// peekFrameSize
return PeekResult.NoFraming  // walker only activates for bounding case
```

For a `@UseCodec(BoundingLengthCodec)` field:

```kotlin
// decode (subsequent fields wrapped in try/finally by buildDecodeFun)
val __nameOuterLimit = buffer.limit()
val name = UserCodec.decode(buffer, context)
UserCodec.applyBound(buffer, name)
// ... subsequent fields decode inside the narrowed limit ...
// finally { buffer.setLimit(__nameOuterLimit) }

// encode
UserCodec.encode(buffer, value.name, context)

// wireSize
return WireSize.BackPatch  // conservative

// peekFrameSize (when prior fields are FixedSize and field type has a budget)
if (stream.available() - baseOffset < priorBytes + 1) return NeedsMoreData
val __namePeekView = stream.peekBuffer(baseOffset + priorBytes, peekBudget)
    ?: return NeedsMoreData
try {
    val __namePriorPos = __namePeekView.position()
    val name = try {
        UserCodec.decode(__namePeekView, DecodeContext.Empty)
    } catch (__e: Throwable) {
        when (__e::class.simpleName) {
            "BufferUnderflowException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException" -> return NeedsMoreData
            else -> throw __e
        }
    }
    val __nameWidth = __namePeekView.position() - __namePriorPos
    val __total = priorBytes + __nameWidth + name.toInt()
    return if (stream.available() - baseOffset >= __total) Complete(__total)
        else NeedsMoreData
} finally {
    (__namePeekView as? PlatformBuffer)?.freeNativeMemory()
}
```

## Conventions worth knowing before touching steps 7–10

1. **`implementsCodecOf` walks `getAllSuperTypes()`.** Step 4 fixed
   the validator's `Codec<T>` check in `ProtocolMessageProcessor.kt`
   to walk the full supertype chain AND accept both `Codec<T>` and
   `BoundingLengthCodec<T>` qnames. KSP doesn't substitute the type
   variable through intermediate interface declarations — the
   transitive `Codec<T>` entry carries an unsubstituted `T`, and the
   concrete type arg lives on whichever interface the codec object
   directly extends. Without this fix, a `BoundingLengthCodec<UInt>`
   impl would fail the `Codec<UInt>` check at validate time.
2. **Underflow exception is platform-dependent.** JVM throws
   `java.nio.BufferUnderflowException` (extends `RuntimeException`,
   NOT `IndexOutOfBoundsException`). JS/WASM/Native throw
   `IndexOutOfBoundsException` / `ArrayIndexOutOfBoundsException`.
   Step 6's peek walker catches `Throwable` with a simpleName
   whitelist on those three names. Codec's own `DecodeException`
   propagates because it's not in the whitelist. Avoids introducing
   an `expect`/`actual` `BufferUnderflowException` typealias for now.
3. **Peek walker precedence over `RemainingBytesScalarList`.** Step 6
   placed the bounding-`UseCodecScalar` walker BEFORE the slice 7b
   `RemainingBytesScalarList → NoFraming` collapse. Reason: a
   bounding codec gives peek the value-driven byte count that bounds
   any trailing `@RemainingBytes` body, mirroring the slice 8
   `RemainingLength` precedence.
4. **`@RemainingBytes @UseCodec val: P` (slice 10a Payload path)
   stays.** `analyzeField`'s order: `@RemainingBytes` block runs
   first; if it returns a `RemainingBytesPayload`, we short-circuit.
   The bare-`@UseCodec` branch only triggers when `@RemainingBytes`
   is absent. Both paths coexist.
5. **`peekBuffer` slow-path returns a pool-acquired buffer.** Caller
   releases via `freeNativeMemory()`. Step 6's emit wraps the peek
   view in `try { ... } finally { (view as? PlatformBuffer)?.freeNativeMemory() }`.
   For non-PooledBuffer chunks the call is a no-op (safe).

## Outstanding work for step 4 — Partial outer-limit-capture extension

**Deferred from step 4** because no current fixture combines a
bounding `UseCodecScalar` with a `RemainingBytesPayload`. Step 9's
PUBLISH migration will need it.

Slice 10f's `buildPartialClassTypeSpec` and `buildPartialEntryFun` /
`buildPartialCompleteFun` capture the outer limit on the Partial when
the shape carries `@RemainingLength`, then restore in the
`complete(...)` try/finally. The detection currently gates on
`hasRemainingLength = shape.fields.any { it is FieldSpec.RemainingLength }`
(approx line 1395 in `CodecEmitter.kt`).

For step 9, this needs to generalize to any bounding field:
```kotlin
val hasBoundingField = shape.fields.any { it.isBoundingShape() }
```

The local-name convention `__<rlName>OuterLimit` is keyed on the
field name. With `UseCodecScalar`, the field name varies — the
emitter already uses `__${field.name}OuterLimit` in
`appendDecodeUseCodecScalar` (step 4) and the same name in
`buildDecodeFun`'s try/finally restore. Keep the convention; the
Partial's stored property name should match (`outerLimit` or
`__<name>OuterLimit` — pick one and apply consistently across
constructor + `complete` body).

## Steps 7–10 landing order

### Step 7 — Add `MqttRemainingLengthCodec` fixture

Self-contained; no other test changes. Lives at:
`buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttRemainingLengthCodec.kt`

Spec from the design doc (lines 167–225):

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

Add a unit test verifying byte-exact wire output matches the slice 8
`appendDecodeRemainingLength` / `appendEncodeRemainingLength` emit
(round-trip a few values: 0, 127, 128, 16_383, 16_384, 0x0FFF_FFFF;
malformed 5-byte input throws).

This step is individually green: no fixture uses the codec yet.

### Step 8 — Migrate `MqttPacket.PingReq` (the smoke test)

`PingReq` is the smallest variant — 2-byte fixed header (packet type
0xC0 + remainingLength = 0). Replace its `@RemainingLength val
remainingLength: UInt = 0u` field with
`@UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u`.

Single annotation swap. Confirm:
- `MqttPacketCodecTest.encodes/decodes/peeks PingReq` stays green.
- `peekFrameSizeForPingReqCompletesAtTwoBytes` (or whatever the test
  is named) reports `Complete(2)`.
- Byte-exact wire output unchanged.

If anything breaks, the bug is in steps 1–6 — fix before continuing.
This is the smoke test that validates the whole chain.

Locations to find (probably):
- Fixture: `buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttPacket.kt`
- Test: `buffer-codec-test/src/commonTest/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttPacketCodecTest.kt`

### Step 9 — Migrate the remaining variants

`MqttPacket.{Connect, Publish, PingResp, Disconnect}` plus standalone
`MqttSubAck`, `MqttConnect`. Each is a 1-line annotation swap
(`@RemainingLength` → `@UseCodec(MqttRemainingLengthCodec::class)`).

After this step, no fixture references `@RemainingLength`. Run all of
`:buffer-codec-test:jvmTest` to confirm. The `MqttPacket.Publish`
variant is the one that exercises the **bounding `UseCodecScalar` +
`RemainingBytesPayload`** combination — this is where the
slice 10f Partial outer-limit-capture extension (deferred from step 4)
must land. Plan:

1. Generalize `hasRemainingLength` in `buildPartialClassTypeSpec`
   to `hasBoundingField` (uses `isBoundingShape()` extension).
2. Have the Partial's `outerLimit` property/parameter wired up
   regardless of which bounding annotation drove the emit. The
   generated decode inside `partial(...)` already emits
   `__<name>OuterLimit` for either field type (step 4's
   `appendDecodeUseCodecScalar` and the existing
   `appendDecodeRemainingLength`).
3. The Partial constructor wiring in
   `buildPartialEntryFun` references `__<rlName>OuterLimit`
   directly — make this generic on the bounding field's name.
4. The `complete(...)` body's `buffer.setLimit(outerLimit)` in
   the finally is unchanged; it reads the constructor-stored field.

If any vector unveils additional Partial integration gaps, address
them here. The `:buffer-flow:CodecConnectionSmokeTest` suite is the
end-to-end check — `aggregatorPathTopicKeyed` exercises PUBLISH
through the typed `Connection<MqttPacket<TextPayload>>` boundary
via the slice 10d.5 aggregator + slice 10c Partial — must stay green.

### Step 10 — Delete `@RemainingLength`

Demolition pass. Remove:
- The annotation declaration in `:buffer-codec/.../annotations/Annotations.kt`.
- `FieldSpec.RemainingLength` sealed member in `CodecEmitter.kt`
  (line ~4402).
- All emit hooks: `appendDecodeRemainingLength`,
  `appendEncodeRemainingLength`, `appendPeekRemainingLength` in
  `CodecEmitter.kt`.
- The `analyzeField` branch for `remainingLengthAnn` (line ~383).
- The mutual-exclusivity check that mentions `RemainingLength` (e.g.
  in `analyzeField`'s `@RemainingBytes` branch).
- `varIntByteCountExpr` if no other caller remains.
- The validator's `RemainingLength` handling in
  `ProtocolMessageProcessor.kt` (search for "RemainingLength" /
  "remainingLength").
- All `is FieldSpec.RemainingLength ->` `when` branches across
  CodecEmitter.kt (the compiler will tell you which ones — exhaustive
  `when` failures will surface them).
- Constants: `REMAINING_LENGTH_QNAME` / `REMAINING_LENGTH_SHORT`
  if defined.
- Tests: any `:buffer-codec-processor:test` cases referencing
  `@RemainingLength`. The `UseCodecScalarValidatorTest` suite stays.

The slice 10c `shouldEmitPartial` carve-out that gates on
`@RemainingLength` (line ~1354) lifts to gate on
`BoundingLengthCodec`-via-`@UseCodec` instead. Pattern:
```kotlin
private fun shouldEmitPartial(shape: CodecShape): Boolean =
    shape.fields.any { it is FieldSpec.RemainingBytesPayload }
```
This already only tests for `RemainingBytesPayload` — re-read the
function to confirm whether the carve-out actually mentions
`RemainingLength`. If it does, replace with
`isBoundingShape()`. (Per the doc, the carve-out semantic is "lift
when the bounding annotation is present"; the new gate is the same
semantic, different mechanism.)

After step 10:
- `:buffer-codec-test:jvmTest`: 215 (all migrated; no test-count
  change expected).
- `:buffer-codec-processor:test`: drops a few cases referencing
  `@RemainingLength`; final count somewhere in the 50s.
- `:buffer-flow:jvmTest`: 36 (unchanged).
- `:buffer:jvmTest`: 1008 (unchanged).

### Step 11 — `@LengthPrefixed @UseCodec` composition — **landed**

Lifted the validator's "not yet supported" diagnostic for
`@LengthPrefixed @UseCodec` and added `FieldSpec.LengthPrefixedUseCodecList`
for the MQTT v5 property-list shape:
`@LengthPrefixed @UseCodec(C::class) val xs: List<E>` where `C :
BoundingLengthCodec<UInt>` and `E` is a `@ProtocolMessage data class`.

**Validator (PMP.kt).** The old `hasOtherFraming` rejection split into
`hasLengthFrom` (still deferred) and `hasLengthPrefixed` (routed to
`validateLengthPrefixedUseCodec`). Diagnostics: target not a Kotlin
`object`; target doesn't implement `BoundingLengthCodec<UInt>`; field
type isn't `kotlin.collections.List<E>`; element type isn't a
`@ProtocolMessage data class`. New helper
`implementsBoundingLengthCodecOfUInt` walks `getAllSuperTypes()` and
matches the `BoundingLengthCodec<UInt>` arg. Scalar
`@LengthPrefixed @UseCodec` still rejects, but with the focused
"must be applied to a `kotlin.collections.List<E>`" diagnostic — the
old "deferred to a later slice" message is gone.

**Analyzer (CodecEmitter.kt).** `analyzeField`'s `lengthPrefixed != null`
branch routes to `analyzeLengthPrefixedUseCodecListField` when
`@UseCodec` is also present. The `@LengthPrefixed val: String` /
`@LengthPrefixed @ProtocolMessage` paths stay untouched.

**Generated decode shape:**

```kotlin
val __<name>OuterLimit = buffer.limit()
val __<name>Length = <codecType>.decode(buffer, context)
<codecType>.applyBound(buffer, __<name>Length)
val <name> = mutableListOf<ElementType>()
try {
    while (buffer.position() < buffer.limit()) {
        <name> += ElementCodec.decode(buffer, context)
    }
} finally {
    buffer.setLimit(__<name>OuterLimit)
}
```

Self-contained `try`/`finally` — restores the outer limit BEFORE
returning. Subsequent fields run at the original outer limit. The
shape is **not** registered in `isBoundingShape()`, so it composes
with an outer bounding `@UseCodec(BoundingLengthCodec)` field
(typical MQTT v5: outer `remainingLength` + inner `properties` bag)
without violating the at-most-one-bounding-field uniqueness check.

**Generated encode shape:**

```kotlin
val __<name>BodyBytes = value.<name>.sumOf {
    (ElementCodec.wireSize(it, context) as WireSize.Exact).bytes
}
<codecType>.encode(buffer, __<name>BodyBytes.toUInt(), context)
for (__elem in value.<name>) {
    ElementCodec.encode(buffer, __elem, context)
}
```

BackPatch element codecs throw `ClassCastException` — matches the
existing `LengthPrefixedMessage` / `RemainingBytesProtocolMessageList`
contract.

**Generated peek shape.** Mirrors the bounding-`UseCodecScalar`
walker (Phase I.1 step 6) — drives the prefix codec against
`stream.peekBuffer(...)`, measures observed codec width, computes
`total = priorBytes + width + decodedValue.toInt()`. Gated on
`priorAreFixed && isTerminal` (the list must be the last field, and
prior fields all `FixedSize`); otherwise `NoFraming`. Peek budget
is hard-coded to 5 bytes (UInt budget) — the codec's value type is
always `UInt` in this slice. Same `BufferUnderflowException` /
`IndexOutOfBoundsException` / `ArrayIndexOutOfBoundsException`
simpleName whitelist as step 6.

**`wireSize` collapses to `BackPatch`** when any
`LengthPrefixedUseCodecList` field is present, unconditionally.
Runtime-Exact promotion via `codec.wireSize(bodyBytes.toUInt())` +
element wireSize sum is a follow-on — no current vector benefits.
The variant `classifyVariantWireSize` mirrors this with a matching
short-circuit so the dispatcher size table skips the `as Exact` cast.

**Test counts at HEAD (step 11 landed):**
- `:buffer-codec-test:jvmTest` — 342 (+13 new
  `lengthprefixedusecodec` package).
- `:buffer-codec-processor:test` — 56 (+3:
  `acceptsLengthPrefixedUseCodecOnProtocolMessageList`,
  `rejectsLengthPrefixedUseCodecWithNonBoundingCodec`,
  `rejectsLengthPrefixedUseCodecWithNonProtocolMessageElement`;
  the existing `rejectsLengthPrefixedUseCodecAsDeferred` test was
  renamed to `rejectsLengthPrefixedUseCodecOnNonListField` and now
  asserts the new focused diagnostic).
- `:buffer-flow:jvmTest` — 36 (unchanged).

**Files touched in step 11:**

```
buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt
buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/ProtocolMessageProcessor.kt
buffer-codec-processor/src/test/kotlin/com/ditchoom/buffer/codec/processor/UseCodecScalarValidatorTest.kt

buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/lengthprefixedusecodec/LengthPrefixedUseCodecListFixtures.kt   (new)
buffer-codec-test/src/commonTest/kotlin/com/ditchoom/buffer/codec/test/protocols/lengthprefixedusecodec/LengthPrefixedUseCodecListCodecTest.kt   (new)
```

**Decisions reaffirmed in step 11:**

- **Codec value type is `UInt` only.** `BoundingLengthCodec<UInt>` is
  the only accepted codec type-arg. Covers MQTT var-byte-int (4-byte
  max), LEB128 (32-bit), sentinel-extended length (4-byte). Wider
  protocols would need a new shape variant; no in-scope vector needs
  it.
- **List<E> with E being `@ProtocolMessage data class` only.** Scalar
  / value-class / Payload elements all reject. The emitter calls
  `<E>Codec.decode` per element, so the element must have a generated
  codec.
- **Self-contained `try`/`finally` (NOT message-wide bounding).**
  The shape narrows the limit locally and restores immediately, so it
  composes with an outer bounding field (slice 10f's
  `__<name>OuterLimit` machinery) without conflict. This is what
  unblocks v5 PUBLISH (outer `remainingLength` + inner `properties`).
- **Peek terminal-only.** Walker computes
  `priorBytes + width + value.toInt()` — assumes the bounded region
  spans to the end of the message. For non-terminal lists, peek
  collapses to `NoFraming`; the outer dispatcher's bounding codec
  (e.g. v5 outer `remainingLength`) owns whole-frame peek.
- **`wireSize` collapses to BackPatch.** Same conservative pattern as
  `UseCodecScalar`. Promote when a vector measurably benefits.

## Validator diagnostics still to add (step 9 follow-up)

When step 9 lands the bounding-`UseCodecScalar` + `RemainingBytesPayload`
combination via PUBLISH, audit `validateUseCodec` and the
`shouldEmitPartial` machinery for any new "X requires Y" message that
made sense for `@RemainingLength` but reads oddly when the source is a
generic codec. Specifically:
- "RemainingLength outer-limit capture" diagnostics — rewrite to
  "bounding length codec outer-limit capture" or similar.
- Any error messages explicitly naming `@RemainingLength` —
  generalize.

## Files I touched in steps 1–6

```
buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/BufferStream.kt
buffer/src/commonTest/kotlin/com/ditchoom/buffer/StreamProcessorPeekBufferTests.kt   (new)

buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/BoundingLengthCodec.kt   (new)

buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/ProtocolMessageProcessor.kt
buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt
buffer-codec-processor/src/test/kotlin/com/ditchoom/buffer/codec/processor/UseCodecScalarValidatorTest.kt   (new)

buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/usecodecscalar/UseCodecScalarFixtures.kt   (new)
buffer-codec-test/src/commonTest/kotlin/com/ditchoom/buffer/codec/test/protocols/usecodecscalar/UseCodecScalarCodecTest.kt   (new)
```

## Decisions explicitly made + reaffirmed during 1–6

- **Caller releases peek view.** `peekBuffer` returns a `ReadBuffer?`
  with the same release contract as `readBuffer`: caller calls
  `freeNativeMemory()`. Step 6's emit wraps the view in try/finally
  to release on every exit path.
- **Cross-platform underflow via simpleName whitelist.** No
  `expect`/`actual` `BufferUnderflowException` — the simpleName
  check on three names (`BufferUnderflowException`,
  `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`)
  covers JVM / JS / WASM / Native.
- **Peek walker only for the bounding case.** Non-bounding
  `UseCodecScalar` falls back to `NoFraming`. Lifting requires a
  vector that benefits — none of the current target protocols need
  it.
- **wireSize collapses to BackPatch for any UseCodecScalar shape.**
  Conservative; runtime-Exact promotion via `codec.wireSize`
  forwarding is a follow-on once a measurable benefit appears.
- **At most one bounding field per message.** Generalized from slice
  8's "at most one `RemainingLength`" via `isBoundingShape()`.
  Multiple `setLimit` calls per decode have ambiguous semantics and
  no real protocol has that wire shape.
- **`implementsCodecOf` accepts both `Codec` and `BoundingLengthCodec`
  qnames.** KSP doesn't substitute the type var through the
  intermediate interface, so the transitive `Codec<T>` carries an
  unsubstituted T; the concrete type arg is on whichever interface
  the codec object directly extends.

## How to start the next session

1. Read this file (`PHASE_I_1_RESUME.md`) first.
2. Read `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md` for the locked
   design.
3. Skim `STAGE_H_RESUME.md` §"Slice 10f shape" and §"Slice 10c
   shape" — those are the patterns step 9 generalizes.
4. Verify branch HEAD: `d1e0fae0`. Run `:buffer-codec-test:jvmTest`
   + `:buffer-codec-processor:test` + `:buffer-flow:jvmTest` to
   confirm green baseline.
5. Start with step 7 (add the codec fixture). Single file, no
   dependencies, ~30 lines.
6. Step 8 next (PingReq smoke test). One-line annotation swap;
   confirm peek + round-trip stay byte-exact.
7. Step 9 — multi-variant migration. The PUBLISH migration is where
   the deferred `Partial` outer-limit-capture extension lands.
8. Step 10 — `@RemainingLength` deletion. Mechanical demolition
   driven by exhaustive-`when` failures + grep.
9. Step 11 — `@LengthPrefixed @UseCodec` composition for the v5
   property-list shape. Landed as part of Phase I.1.

The 342 + 56 + 36 + 1008 test baseline must stay green throughout
follow-on work. Phase I.1 is now complete; J.M.5 (MQTT v5 modeling)
is the next session.
