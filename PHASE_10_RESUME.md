# Phase 10 — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the Q1–Q5 walk session (resolved
> 2026-05-02) to the slice-4 redesign-2 implementation session.
> Once the implementation lands, delete this file in the same
> commit that closes step 5 of the test-driven sequence below.

Resuming Phase 10 codec rewrite on the buffer repo at
`/home/rbehera/git/buffer`, branch `feature/directional-codec`,
on top of commits `2bbc580` (slice 4 redesign-1, typed body) and
`1be3bb3` (§8 validator).

## TL;DR

The slice-4 impossible-state class — `WavFmtChunk(fourCC, chunkSize,
body)` where `chunkSize: UInt` and `body.wireSize()` independently
encode the same quantity — is being closed by widening
`@LengthPrefixed` to cover `@ProtocolMessage` data class fields and
narrowing `@LengthFrom` to remote-prefix only. Q1–Q5 are answered.
The work is mechanical; the only design call left is the validator
diagnostic wording.

## Locked decisions (Q1–Q5)

### Q1 — `@LengthPrefixed` widens (natural extension)

`@LengthPrefixed` already means "length || value." The annotation's
own kdoc (`buffer-codec/.../Annotations.kt:90`) already says "String
**or payload** field," anticipating this extension. Widening covers
`@ProtocolMessage` data class fields under identical semantics:
encode emits the prefix carrying the body's `wireSize` in bytes;
decode reads the prefix, `setLimit`-bounds, decodes, restores outer
limit. Stage H's `Payload` SAM eventually slots into the same
annotation as a third value shape (String / message / Payload).

### Q2 — wire-mirroring, soft rule with length-carrier carve-out

"Data class mirrors wire format one-to-one" remains the principle.
Carve-out: redundant length carriers — fields whose only purpose is
to bound a sibling — are not constructor parameters; they are
expressed by `@LengthPrefixed` on the bounded field. Other redundant
fields (checksums, magic numbers, padding) **stay as constructor
parameters.** The carve-out is narrow and length-specific.

### Q3 — `@LengthFrom` narrows to remote-prefix (exclusive)

Adjacent-prefix migrates entirely to `@LengthPrefixed`. The
processor rejects `@LengthFrom("X")` on field F when X is the field
immediately preceding F in the same parent. `@LengthFrom`'s
remaining valid use is genuine remote-prefix — length carried in a
non-adjacent field, often parsed by a different codec or sitting
several positions away (MQTT-style header-bounded payloads, parent-
passed bounds via `@DispatchOn`, etc.). Mental model:

- `@LengthPrefixed` — "the length is mine, emit it adjacent to the value."
- `@LengthFrom("X")` — "I'm reading a length someone else already wrote down for a separate reason; X exists as a constructor parameter because the consumer cares about it as a number, not just as my bound."

### Q4 — validator catches `Exact`-disagreement (compile-time)

For surviving remote-prefix `@LengthFrom` uses, the processor extends
to walk references and catch `Exact`-disagreement when both sides
are determinable at compile time. The check is **opportunistic**:
it fires only when the referenced field is fixed-`Exact` *and* the
bound field's codec reports `Exact(K)`. If either side is runtime-
derived (varint length, computed sum, etc.) the check silently
passes — runtime decode remains the source of truth there. Diagnostic
shape: "field F bound by `@LengthFrom(\"X\")` reports `Exact(K)` but
X is fixed at V ≠ K — values disagree at compile time."

### Q5 — amendment-in-place, single change set

Slice 4 is amended in place. After Q3's exclusive-migration, the
current `@LengthFrom("chunkSize")` becomes a compile error under the
new validator rule, so the code must change in lockstep with the
processor — there is no "additive new slice" path that leaves slice
4's code intact. All six end-state pieces below land together; no
follow-ups carry forward.

## Concrete end state

### Code (`buffer-codec-test/src/commonMain/.../riff/`)

- `WavFmtChunk.kt` — data class becomes 2-field:

  ```kotlin
  @ProtocolMessage(wireOrder = Endianness.Little)
  data class WavFmtChunk(
      val fourCC: UInt,
      @LengthPrefixed(LengthPrefix.Int) val body: WavFmtBody,
  )
  ```

  `WavFmtBody` (the inner `@ProtocolMessage`) is unchanged.

- `WavFmtBodyCodec.kt` — unchanged. It stays as the hand-written
  reference codec for `WavFmtBody`. It is no longer referenced by
  `WavFmtChunk` via `@UseCodec` — `WavFmtChunkCodec` calls it
  directly because slice 4 is hand-written until Stage A's emitter
  lands.

- `WavFmtChunkCodec.kt` — encode/decode/peek updated:
  - **encode**: `writeUInt(fourCC)` → write 4-byte LE prefix sourced
    from `(WavFmtBodyCodec.wireSize(body, context) as Exact).bytes`
    → `WavFmtBodyCodec.encode(buffer, body, context)`.
  - **decode**: `readUInt() → fourCC` → read 4-byte LE prefix into
    `prefix` → `outerLimit = buffer.limit(); setLimit(position +
    prefix)` → `body = WavFmtBodyCodec.decode(...)` → restore
    `outerLimit` → `WavFmtChunk(fourCC, body)`.
  - **wireSize**: `Exact(8 + bodyExact)` (4 fourCC + 4 prefix + 16
    body = 24).
  - **peekFrameSize**: identical wire — read 4-byte LE prefix at
    `baseOffset + 4`, return `Complete(8 + prefix)`.

- `WavFmtChunkCodecTest.kt` — round-trip tests update to construct
  `WavFmtChunk(fourCC, body)`. Wire-bytes assertions unchanged
  (same 24 bytes on the wire). Add a constructor-arity test
  (`WavFmtChunk::class.constructors.first().parameters.size == 2`)
  to lock the impossible-state-class fix. Peek tests update with
  the new constructor.

- `RiffChunkHeaderCodec`/`RiffChunkHeader` — **unchanged.** The
  header data class stays as `(fourCC, chunkSize)`; both fields
  are constructor parameters because slice 1's vector is the
  *header* — when used standalone the consumer cares about
  `chunkSize` as a number for navigating the rest of the RIFF
  file. The carve-out applies to `WavFmtChunk` (a full chunk)
  where `chunkSize`'s only role would be bounding `body`.

### Validator (`buffer-codec-processor/.../ProtocolMessageProcessor.kt`)

Three new rules slot alongside the §8 raw-bytes ban (no new
compilation pass):

- **Rule R1 — adjacent-`@LengthFrom` rejection (scoped).**
  `@LengthFrom("X")` on field F where X is the field immediately
  preceding F in the same parent is a compile error **when F has a
  viable `@LengthPrefixed` migration target** (String or
  `@ProtocolMessage` data class type). Bound fields whose type
  extends `com.ditchoom.buffer.codec.Payload` are skipped — see the
  "Stage-H follow-up" carryover below for why and when this exclusion
  goes away. Diagnostic names both fields and points at
  `@LengthPrefixed` as the replacement.
- **Rule R2 — `@LengthFrom` Exact-disagreement. Deferred.** Original
  Q4 spec called for an opportunistic check: fire when X is
  fixed-Exact (`UByte = 5u`) and the bound field's codec reports
  `Exact(K ≠ 5)`. KSP 2.3.0's `KSValueParameter` exposes
  `hasDefault: Boolean` only — the default-value expression is not
  reachable without source-file parsing. Combined with the empirical
  finding that no remote-prefix `@LengthFrom` callsites exist in
  this ecosystem (slice 4 migrates to `@LengthPrefixed`; MQTT v5's
  `CorrelationData` / `AuthenticationData` are adjacent and migrate
  the same way once Stage H widens `@LengthPrefixed`-on-`@Payload`),
  R2 has no real target population today. Re-open R2 when (a) the
  first genuine remote-prefix protocol lands and (b) we adopt a
  KSP-friendly mechanism for "fixed value" — likely a marker
  annotation on the carrier rather than a literal default.
- **Rule R3 — `@LengthPrefixed` accepts `@ProtocolMessage` field
  type.** Verify today's validator state and adjust if it constrains
  the annotation to `String`-typed fields. Stage A's emitter
  contract inherits this. (R3 does *not* yet widen to
  `@Payload`-typed type parameters; that's Stage H — see follow-up.)

Validator unit tests for each: positive (legitimate uses pass) and
negative (diagnostic fires with expected message and field-path).
R1 includes an explicit Payload-carve-out test asserting the rule
stays silent on Payload-extending bound fields.

### Annotation surface (`buffer-codec/.../Annotations.kt`)

- `@LengthPrefixed` kdoc broadens — the existing "String or payload
  field" wording is honored explicitly, with an example showing a
  `@ProtocolMessage` data class field.
- `@LengthFrom` kdoc narrows — the current "String field" wording is
  obsolete (slice 4 already used it on a message field). Replace
  with "remote-prefix only — length carried in a non-adjacent field.
  For adjacent length carriers, use `@LengthPrefixed`." Example
  uses a remote-prefix shape.

### Doctrine (`PHASE_9_RESET.md`)

Locked Decisions there is a numbered table (rows 1–8 today; section
header at line 101). Add row 9, one-line summary:

> | 9 | Wire-mirroring carve-out: redundant length carriers (fields whose only purpose is to bound a sibling) are expressed by `@LengthPrefixed` on the bounded field, not as constructor parameters. Checksums, magic numbers, and padding stay as constructor parameters. |

The fuller statement of the carve-out lives in this briefing's Q2
section above and in the slice-4 correction note below. The
section's preamble (line 103) claims a `PHASE_10_DESIGN.md`
authoritative file — that file does not exist; do not create it.
The table row is the durable doctrine record. This is the only
`PHASE_9_RESET.md` edit.

### Design notes (`PHASE_10_DESIGN_NOTES.md`)

Single correction note prepended to the slice 4 walk (~line 1267):

> **Correction note (redesign-2).** The locked
> `@LengthFrom("chunkSize") @UseCodec(WavFmtBodyCodec::class)` shape
> was superseded after the wire-mirroring carve-out (see
> `PHASE_9_RESET.md` "Locked Decisions") narrowed `@LengthFrom` to
> remote-prefix only. Slice 4's vector now exercises widened
> `@LengthPrefixed` on a `@ProtocolMessage` field. See commit
> `<hash>`. The walk text below is preserved for archival continuity;
> the live wire-vector is byte-identical, only the annotation
> surface changed. `@UseCodec` proof migrates exclusively to slice 9.

This is the only `PHASE_10_DESIGN_NOTES.md` edit. Do not rewrite the
slice 4 walk body; the correction note sits above it.

### Cleanup

- Delete `PHASE_10_RESUME.md` (this file) in the same commit that
  closes step 5 of the test-driven sequence.

## Test-driven sequence

Each step writes failing tests first, makes them pass, runs the
relevant Gradle target, then moves to the next step. No accumulating
TODOs across steps.

**Build-state note.** Once R1 lands (step 1), slice 4's *current*
code stops compiling because the existing `@LengthFrom("chunkSize")`
on `body` is the exact pattern R1 forbids. Between steps 1 and 4 the
**full build is intentionally red** — only the per-step Gradle
target is green. That's expected; the full check at step 6 is the
gate. Run `./gradlew build` only at step 6, not between intermediate
steps. Bundle steps 1–5 into commits sized for review (one commit
per step is fine; rolling steps 1–4 into one is also fine if the
diff stays readable). Do not push individual intermediate commits to
a remote where CI will run them red — push only after step 6 is
green.

1. **Validator R1 (scoped) — DONE.** Two failing tests + one
   carve-out test landed:
   - `firesOnAdjacentLengthFromString` — adjacent on `String`-typed
     field → compile error.
   - `firesOnAdjacentLengthFromMessageBody` — adjacent on
     `@ProtocolMessage` data class field → compile error.
   - `acceptsAdjacentLengthFromOnPayloadField` — adjacent on
     Payload-extending field → silent (Stage-H carve-out).
   - Plus negative cases for non-adjacent and first-parameter shapes.
   `validateAdjacentLengthFrom` in `ProtocolMessageProcessor` skips
   when `payloadType.isAssignableFrom(boundFieldType)`. The
   `payloadType: KSType?` impossible-state model was collapsed at the
   resolver boundary in `process()` — it's now non-null below.
   `:buffer-codec-processor:test` 12/12 green.
2. **Validator R2 — deferred** (see "Validator" section above for
   full reasoning). Step 2 is a no-op in this work; R2 reopens when
   the first remote-prefix protocol lands or when a marker-annotation
   mechanism for "fixed value" is introduced. Briefing's original
   R2-firing example (`UByte = 5u` literal default) is incompatible
   with KSP 2.3.0's API surface; no source-file parsing path will be
   pursued.
3. **Validator R3** — failing test (if needed): fixture with
   `@LengthPrefixed val body: SomeMessage` where `SomeMessage` is
   `@ProtocolMessage`. Expect validator to pass. Verify today's
   validator does not reject; if it does, adjust. Green. R3 stays
   scoped to `String` + `@ProtocolMessage`; Payload-typed widening is
   Stage H.
4. **Slice 4 redesign-2** — update `WavFmtChunk` to 2-field shape.
   Existing slice-4 tests fail to compile (constructor arity,
   validator R1 fires on the old shape). Update `WavFmtChunkCodec`
   encode/decode/peek + `WavFmtChunkCodecTest` (round-trip,
   constructor-arity, peek). `:buffer-codec-test:jvmTest` green.
5. **Docs** — annotation kdoc updates, design-notes correction note,
   `PHASE_9_RESET.md` Locked Decisions row 9 (table-row format, not
   a paragraph), delete this file. No test impact. Single docs
   commit (or rolled into step 4's commit if step 4 is small enough
   — author's call).
6. **Full check** — `./gradlew :buffer-codec-processor:test
   :buffer-codec-test:jvmTest` green before requesting push
   approval.

## Carryovers (still in force)

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- DO NOT modify `PHASE_10_DESIGN_NOTES.md` or `PHASE_9_RESET.md`
  beyond the single correction note and single doctrine paragraph
  specified above. Anything else needs explicit user authorization.
- mavenLocal republish stays deferred until after Stage H.
- Apple build verification carryover from commits `f0a68a9` /
  `318b638` (touched `MutableDataBuffer` paths) — confirm on a
  macOS host before any merge to main. Not blocking this work.
- Eight `*_BUG.md` / `*_ISSUES.md` notes at repo root remain out of
  scope. Don't sweep them.
- Don't update `CLAUDE.md` or KDoc prose for slice semantics; the
  test suites are the executable doc. (KDoc edits in this work are
  the annotation surface — `@LengthPrefixed` and `@LengthFrom` —
  which are part of the public API contract, not slice prose.)
- §8 validator stays. R1 (scoped) lands alongside it; R2 deferred;
  R3 lands in step 3.
- **Stage-H follow-up (durable record).** When Stage A's emitter and
  Stage H's `@LengthPrefixed`-on-`@Payload` widening land together,
  three coordinated changes happen in one commit set:
  1. MQTT v5's `CorrelationData<@Payload CD>` and
     `AuthenticationData<@Payload AD>` migrate to the 2-field shape
     `@LengthPrefixed(LengthPrefix.Short) val data: CD` (and the AD
     equivalent), dropping the `length` constructor parameter.
     `length` is wire-only today — its sole non-test reader is
     `MqttPropertyCodecExt.kt:98-99` (`property.length.toInt()`
     inside `mqttPropertySize`), which is itself a hand-walked
     wire-size computation that exists only because code-gen isn't
     running yet. Once the generator emits a `wireSize()` for these
     classes, `mqttPropertySize` is deleted wholesale; `length`
     disappears with it. Constructor sites update accordingly:
     `ConnAckProperties.kt:97` (`AuthenticationData(authentication
     .data.remaining().toUShort(), authentication.data)` becomes
     `AuthenticationData(authentication.data)`), test fixtures, and
     the JS IDB shim.
  2. R1's Payload exclusion is removed (the carve-out's premise
     evaporates once `@LengthPrefixed` covers Payload).
  3. R3 widens to accept `@Payload`-typed type parameters; validator
     unit tests expand to cover the Payload-on-`@LengthPrefixed`
     shape.
  This bundle is *not* part of Phase 10; it's the durable record of
  the deferred work so the next session can pick it up. The two MQTT
  data classes are the only non-slice-4 callsites of `@LengthFrom`
  in either repo today.

## Read these to load context

- `CLAUDE.md` — `BufferFactory` discipline, wrapper transparency,
  generated `peekFrameSize` contract.
- `PHASE_9_RESET.md` — locked decisions, Stages A–H plan, constraints.
- `PHASE_10_DESIGN_NOTES.md` slice 4 walk (line ~1267) — original
  `@LengthFrom("chunkSize")` lock, kept for archival continuity.
- `PHASE_10_DESIGN_NOTES.md` §8 (line ~198) — `Payload` marker;
  enforced by validator since `1be3bb3`. Stage H still owns the SAM.
- `buffer-codec-test/.../riff/WavFmtChunk.kt` — current shape, the
  thing being amended.
- `buffer-codec-test/.../riff/WavFmtBodyCodec.kt` — unchanged
  reference codec; `WavFmtChunkCodec` calls into it.
- `buffer-codec/.../Annotations.kt` — `@LengthPrefixed` (line 102),
  `@LengthFrom` (line 141); kdoc updates land here.
- `buffer-codec-processor/.../ProtocolMessageProcessor.kt` — §8
  validator; R1/R2/R3 rules slot here.

## Five most recent commits

```
5b7552c docs: add PHASE_10_RESUME.md briefing for next session       ← Q1–Q5 walk briefing; this file overwrites it (uncommitted)
1be3bb3 buffer-codec-processor: enforce §8 raw-bytes ban in @ProtocolMessage fields
2bbc580 buffer-codec-test: redesign slice 4 with typed WAV fmt body  ← redesign-1 (typed body)
6ffead9 buffer-codec-test: type-check slice 4 (RIFF chunk + @LengthFrom + @UseCodec)  ← original; superseded by 2bbc580
897df44 docs: add PHASE_10_RESUME.md briefing for next session       ← deleted in 6ffead9; predecessor of 5b7552c
```

Working-tree state at hand-off: `PHASE_10_RESUME.md` is modified
(this file) but uncommitted. Implementation-session is expected to
land R1/R2/R3 + slice-4 redesign-2 + docs as one or two commits and
delete this file in the same change set per step 5.

## When this file's job is done

Delete `PHASE_10_RESUME.md` in the same commit that closes step 5
of the test-driven sequence. The locked decisions are absorbed into
the annotation kdoc, the `PHASE_9_RESET.md` Locked Decisions row 9,
and the slice-4 correction note in the design notes — three durable
locations. This ephemeral hand-off has no further role.
