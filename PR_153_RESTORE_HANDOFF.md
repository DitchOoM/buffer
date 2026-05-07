# PR #153 restore handoff — issues #150 / #151 / #152

Repo: `/home/rbehera/git/buffer`
Branch: `feature/directional-codec` (current tip ~`9f1f6322` "Phase J.M.5 slice 15d")
Author of handoff: 2026-05-07 session

## TL;DR

Three GitHub issues that were fixed by PR #153 (commit `357dd083`,
merged 2026-04-19) regressed when the Phase 9 reset (commit
`4fbe05a2`, 2026-04-29) stripped the codec processor back to
scaffolding. The processor has been rebuilt through Stage A → Phase
J.M.5 but PR #153's three features have not been restored. **Do NOT
cherry-pick `357dd083`** — the file layout it touched
(`CodecGenerator.kt`, `FieldAnalyzer.kt`, `FieldCodeEmitter.kt`,
`PayloadEmitter.kt`, `PeekFrameSizeEmitter.kt`) was collapsed by the
rebuild into a single `CodecEmitter.kt` + `ProtocolMessageProcessor.kt`
monolith. Port the *shapes* and the *test fixtures*, not the file
edits.

## Verifying the regression status before starting

```bash
# All three issues are CLOSED on GitHub via PR #153.
gh issue view 150 --repo DitchOoM/buffer --json state,title
gh issue view 151 --repo DitchOoM/buffer --json state,title
gh issue view 152 --repo DitchOoM/buffer --json state,title

# But the fix is wiped on the current branch:
git log --oneline 357dd083..HEAD -- buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecGenerator.kt
# Returns the strip commit `4fbe05a2 codec: Stage 0 — strip processor to scaffolding`.

# The PR diff is the canonical recipe:
git show 357dd083 --stat
git show 357dd083 -- buffer-codec-test                           # fixtures
git show 357dd083 -- buffer-codec-processor/src/test             # processor tests
git show 357dd083 -- buffer-codec/src/commonMain/.../annotations # KDoc + any annotation params
```

## Issue-by-issue scope

### Issue #150 — `@ProtocolMessage` on `data object`

**What's broken today.** `ProtocolMessageProcessor.kt:1262` reads:

```kotlin
val isDataClass = elementDecl != null && Modifier.DATA in elementDecl.modifiers
val isSealed = elementDecl != null && Modifier.SEALED in elementDecl.modifiers
if (!isProtocolMessage || (!isDataClass && !isSealed)) {
    logger.error(...)
    return
}
```

`Modifier.DATA in modifiers` is also true for `data object`, but the
broader analyzer assumes a primary constructor exists. A `data object`
has no constructor parameters and the analyzer drops it.

**What PR #153 added.** A data-object variant carries zero wire bytes
beyond its `@PacketType` discriminator. Decode returns the singleton
instance; encode writes nothing past the dispatch tag. Same dispatcher
shape as a `data class` variant with no fields.

**Target shape (from PR #153 fixture `CommandPayloadProtocol.kt`):**

```kotlin
@ProtocolMessage(Endianness.Little)
sealed interface CommandPayload {
    @PacketType(0x22) @ProtocolMessage
    data class SetRgbState(val dutyR: UByte, val dutyG: UByte, val dutyB: UByte) : CommandPayload

    @PacketType(0x23) @ProtocolMessage
    data object GetRgbState : CommandPayload
}
```

**Implementation sketch (port to current monolith):**

1. In the variant analyzer, add a branch: when `classKind == OBJECT`
   AND the class has no `@DispatchOn` AND it carries `@PacketType`,
   emit a `CodecShape` with empty `fields` and a constructor reference
   that resolves to the object's `INSTANCE` getter.
2. In `buildDecodeFun` for an empty-fields shape, emit
   `return <ObjectName>` instead of `return <Type>(...)`.
3. In `buildEncodeFun` for an empty-fields shape, emit nothing in the
   body (the dispatcher already writes the discriminator).
4. In `buildWireSizeFun`, return `WireSize.Exact(0)` for empty-fields
   variants. The dispatcher's `VariantWireSize.LiteralExact(0)` already
   handles the rollup correctly.

**Tests to land.** PR #153 added `DataObjectCodegenTest.kt` —
reconstruct it. At minimum:

- A standalone `@ProtocolMessage data object` accepts and round-trips
  through its own `<Name>Codec` (decoding returns `INSTANCE`).
- A sealed parent with mixed `data class` / `data object` variants
  dispatches correctly on `@PacketType`.
- An `object` (without `data`) is also accepted (PR #153 covered both).

### Issue #151 part 1 — length annotations on nested `@ProtocolMessage` fields

**What's broken today.** `CodecEmitter.kt:442` (the `@LengthFrom`
analyzer entrypoint) branches on `String` and `List<T>`:

```kotlin
return when (typeQname) {
    "kotlin.String" -> analyzeLengthFromStringField(...)
    "kotlin.collections.List" -> analyzeLengthFromListField(...)
    else -> null   // silently drops nested @ProtocolMessage
}
```

A field shaped `@LengthFrom("length") val body: BodyMessage` (where
`BodyMessage` is `@ProtocolMessage`) falls through to `else -> null`
and the parent codec is silently skipped — same failure mode that hit
my `@RemainingBytes String` work this session, fixed by analogously
adding a branch.

**What PR #153 added.** Treat a nested `@ProtocolMessage` field
attached to a length annotation by handing the inner message a slice
of the buffer (`@LengthFrom`/`@LengthPrefixed`) or `buffer.remaining()`
(`@RemainingBytes`). Per the PR notes:

> Length annotations (@LengthFrom, @LengthPrefixed, @RemainingBytes)
> can now attach directly to nested @ProtocolMessage fields. Enables
> framing where length covers payload + trailer by wrapping them in a
> @ProtocolMessage.

**Target shape (from PR #153 fixture `FramedCommandProtocol.kt`):**

```kotlin
@ProtocolMessage(Endianness.Little)
sealed interface Frame {
    @PacketType(0x0A) @ProtocolMessage
    data class Command(
        val counter: UShort,
        val length: UShort,
        @LengthFrom("length") val data: CommandPayload,  // nested @ProtocolMessage, NO @UseCodec
    ) : Frame
}
```

**Implementation sketch.** Add a new `FieldSpec.LengthFromMessage`
(mirror of `LengthFromString` and `LengthFromList`):

```kotlin
data class LengthFromMessage(
    override val name: String,
    val ownerSimpleName: String,
    val source: LengthSource,
    val messageType: ClassName,
    val codecType: ClassName,
) : FieldSpec
```

Then:

1. In the `@LengthFrom` analyzer, after the `String` and `List` branches,
   check if the field type is a `@ProtocolMessage` data class or sealed
   parent. If yes, return `LengthFromMessage` with the resolved codec
   reference (`<MessageType>Codec`).
2. Decode: capture outer limit, narrow `buffer.setLimit(buffer.position()
   + lengthValue)`, call `<codecType>.decode(buffer, context)`, restore
   outer limit in `try { ... } finally`. Same outer-limit dance as
   `LengthPrefixedMessage`.
3. Encode: call `<codecType>.encode(buffer, value.<name>, context)`. The
   length sibling gets back-patched via the existing `LengthSource`
   machinery.
4. wireSize: collapse to BackPatch (mirror of `LengthPrefixedMessage`).
5. Add the same shape for `@LengthPrefixed val: T : @ProtocolMessage` if
   PR #153 covered it (check the diff — `LengthPrefixedMessage` already
   exists for this case so probably no-op).
6. For `@RemainingBytes val: T : @ProtocolMessage` (single nested
   message, not a list), add `RemainingBytesMessage` — analogous to my
   recent `RemainingBytesString` work.

**Tests to land.** PR #153 added `FramedCommandRoundTripTest.kt` and
likely a processor test. Reconstruct both:

- Round-trip a frame whose inner `@ProtocolMessage` body is bounded by a
  `@LengthFrom` sibling.
- The body decode is bounded — trailing bytes past the body's wire size
  must remain unread for the parent codec to consume.
- Validator rejects forms that would conflict (e.g.,
  `@LengthFrom @UseCodec` on the same field if mutually exclusive).

### Issue #151 part 2 — `@RemainingBytes` non-terminal with auto-reserved fixed trailers

**What's broken today.** `CodecEmitter.kt:225-231`:

```kotlin
for ((index, field) in fields.withIndex()) {
    if (field is FieldSpec.LengthFromString && index != fields.lastIndex) return null
    if (field is FieldSpec.LengthFromList && index != fields.lastIndex) return null
    if (field is FieldSpec.RemainingBytesScalarList && index != fields.lastIndex) return null
    if (field is FieldSpec.RemainingBytesProtocolMessageList && index != fields.lastIndex) return null
    if (field is FieldSpec.RemainingBytesPayload && index != fields.lastIndex) return null
    if (field is FieldSpec.RemainingBytesString && index != fields.lastIndex) return null
}
```

Hard terminal-only rule.

**What PR #153 added.**

> @RemainingBytes no longer has to be the last field. Trailing fields
> with fixed wire size (primitives, value classes, fixed custom fields)
> are auto-reserved. Variable-size trailers are rejected with a
> field-specific error.

The body decode treats `buffer.remaining() - <reservedTrailingBytes>`
as the size handed to the `@RemainingBytes` field. The reserved
trailing extent is the sum of `wireBytes` for the trailing fields
(which must all be `FixedSize`).

**Target shape (from PR #153 fixture `TrailingChecksumProtocol.kt`):**

```kotlin
@ProtocolMessage(Endianness.Little)
data class DataPacket(
    @RemainingBytes val payload: List<UByte>,
    val checksum: UByte,
)
```

**Implementation sketch.**

1. Replace the terminal-only check with: "must be terminal, OR every
   subsequent field must be `FieldSpec.FixedSize`." If the latter, sum
   their `wireBytes` into a `reservedTrailingBytes` value attached to
   the `RemainingBytes*` field at analysis time.
2. Decode emit for the `RemainingBytes*` field becomes:
   `val <name> = ...read until buffer.limit() - <reservedTrailingBytes>...`
   For lists / strings this changes the loop bound; for the
   `RemainingBytesPayload` shape the bounded slice's limit is narrowed
   before delegating.
3. Encode emit is unchanged for the `RemainingBytes*` field itself; the
   trailing fields encode normally after.
4. wireSize: still BackPatch (the `RemainingBytes*` field's size still
   isn't known up front).
5. Validator rejects a non-terminal `@RemainingBytes` if any trailing
   field is variable-size — emit a focused error message.

**Tests to land.** PR #153 added `TrailingChecksumRoundTripTest.kt`.
At minimum:

- `@RemainingBytes val payload: List<UByte>` followed by `val checksum:
  UByte` round-trips: encode writes the payload bytes then the checksum;
  decode reads `remaining - 1` payload bytes then 1 checksum byte.
- Validator rejects `@RemainingBytes ...` followed by a variable-size
  field (e.g., `@LengthPrefixed val: String`) with a field-specific
  error.

### Issue #152 — `@UseCodec` KDoc clarification

**What's broken today.** `Annotations.kt:325-365` has the older
phrasing:

> Delegates field decoding/encoding to an existing [Codec] object.
>
> Use this instead of writing a full SPI module when you need a custom
> field type.

**What PR #153 added.** Updated wording (see `git show 357dd083 --
buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/annotations/Annotations.kt`):

> **Use this only for custom, hand-written codecs** (for example,
> variable-byte-integer encoders or image-bitmap parsers). If the
> field's type is itself annotated with `@ProtocolMessage`, declare the
> field with that type directly instead — the processor generates the
> codec by convention and wires it up automatically, including sealed
> dispatch and forward references to codecs generated in the same
> compilation round. `@UseCodec` cannot forward-reference a
> KSP-generated codec class.

Plus the nested-`@ProtocolMessage` example block.

**Implementation.** Pure KDoc. Reconstruct the wording from the PR
diff. Becomes meaningful once #151 part 1 is in (since the
recommendation depends on nested `@ProtocolMessage` direct attachment
working).

## Suggested execution order

1. **Issue #150 first.** Smallest scope, least entanglement — single
   new `data object` branch in the variant analyzer + empty-fields
   shape in three emit functions. Adds confidence in the rebuild
   plumbing before touching the harder issues.
2. **Issue #151 part 1 next.** Adds `LengthFromMessage` (and
   `RemainingBytesMessage` if not covered by `LengthPrefixedMessage`).
3. **Issue #151 part 2.** Lifts the terminal-only rule. Touches the
   most code paths (every `RemainingBytes*` shape's emit needs to
   handle `reservedTrailingBytes`).
4. **Issue #152 last.** KDoc only; reads cleanest after #151 part 1
   demonstrates the recommended path.

Suggested phase label: `Phase J.M.6 — restore PR #153` with one slice
per issue (J.M.6.a / J.M.6.b / J.M.6.c / J.M.6.d).

## Don't break

- 78 processor unit tests (`./gradlew :buffer-codec-processor:test`)
- 542 buffer-codec-test JVM tests (`./gradlew :buffer-codec-test:jvmTest`)
- The `protocols/websocket/` fixture from this session
  (uses plain `@RemainingBytes val payload: String` and
  `@RemainingBytes val reason: String` — would become non-terminal
  invalid if #151 part 2 lands, but the WS fixture has every
  `@RemainingBytes` field already in last position so it should be
  unaffected; sanity-check after each slice).
- The MQTT v3 / v5 fixtures — heaviest exercise of the codec emitter on
  this branch. Run `./gradlew :buffer-codec-test:jvmTest --tests
  '*Mqtt*'` after each slice to confirm no regression.

## Files to read before starting

- `git show 357dd083 --stat`
- `git show 357dd083 -- buffer-codec-test/src/commonMain` — fixture shapes
- `git show 357dd083 -- buffer-codec-test/src/commonTest` — round-trip tests
- `git show 357dd083 -- buffer-codec-processor/src/test` — processor tests
- `PHASE_9_RESET.md` — context for why we got here
- `PHASE_J_M_5_SLICE_14C_HANDOFF.md`, `PHASE_J_M_5_SLICE_15_HANDOFF.md`
  — recent slice-handoff style this branch uses
- The current monolith: `buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/CodecEmitter.kt`
  + `ProtocolMessageProcessor.kt`. The `FieldSpec` sealed hierarchy
  starts at line ~6629.

## Map of the recent emitter sites you'll touch

The `@RemainingBytes String` work I landed this session is the closest
template for the analyzer + emit flow. It touched these sites — every
new `FieldSpec` will need similar coverage:

- Analyzer entry: `CodecEmitter.kt:432` (the `if (typeQname != ...)
  return null` early-out)
- Terminal-only check: `CodecEmitter.kt:225-231`
- Decode dispatch: `CodecEmitter.kt:2353` (line numbers approximate
  after recent edits)
- Encode dispatch: `CodecEmitter.kt:2401`
- `partialFieldTypeName`: `CodecEmitter.kt:2740` (defensive `error()`
  for shapes that aren't compatible with the Partial decode pattern)
- `buildWireSizeFun` early-return: `CodecEmitter.kt:2835`
- `buildPeekFrameFun` NoFraming early-return: `CodecEmitter.kt:3026`
- `buildPeekFrameFun` sequential-walk error: `CodecEmitter.kt:3387`
- `VariantWireSize` BackPatch: `CodecEmitter.kt:5587` and the
  exhaustive `when` at `~5654`

## Caveat on file paths

The line numbers above are accurate as of `9f1f6322`. Subsequent slices
may shift them. Use `grep -n "RemainingBytesScalarList"` /
`"RemainingBytesString"` to find the canonical list of every site any
existing `RemainingBytes*` shape touches — then add the new shape at
each one.
