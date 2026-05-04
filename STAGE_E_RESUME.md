# Stage E — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the Stage D closeout session
> (resolved 2026-05-03) to the Stage E planning + implementation
> session. Once Stage E's first slice lands, delete this file in the
> same commit.

Resuming on the buffer repo at `/home/rbehera/git/buffer`, branch
`feature/directional-codec`, on top of four **local-only** Stage D
commits at the tip:

- `e3b0f37` — Stage D slice 5: JFR allocation regression for sealed
  dispatcher
- `f237c39` — Stage D slice 3: KSP negative-case tests for sealed
  dispatcher
- `e16d10d` — Stage D slice 2: emit `@PacketType` sealed dispatcher
  + Command vector
- `6016aea` — Stage D slice 1: lock unknown-discriminator behaviour
  (Locked Decision row 17)

Plus the Stage A + B + C tips below them. None of the 99 local
commits has been pushed (per the standing "do not push without
confirmation" carryover).

## TL;DR

Stages A–D are closed. The KSP emitter generates working codecs for
fixed-size scalars (signed and unsigned), `@WireBytes` narrowing,
`@WireOrder` overrides, top-level `@JvmInline value class` wrappers,
`@LengthPrefixed @ProtocolMessage`-typed terminal fields,
`@LengthPrefixed val: String` terminal fields with
`WireSize.BackPatch`, and **simple `@PacketType` sealed dispatch**:
1-byte discriminator, exhaustive `when`, per-variant `wireSize`
tightness (literal `Exact(1 + N)` for all-scalar variants, `BackPatch`
for variants whose terminal is `@LengthPrefixed val: String`, runtime
`Exact(1 + variant.bytes)` for `@LengthPrefixed @ProtocolMessage`
terminals), drip-feed-correct `peekFrameSize`, and fail-loud
`DecodeException` on unknown discriminator from both `decode` and
`peekFrameSize` (Locked Decision row 17). The validator enforces
§8 raw-bytes ban, R1 (adjacent `@LengthFrom`), R3 (`@LengthPrefixed`
on `@ProtocolMessage` data class fields, widened to `String`), R4
(`@WireBytes` width range), and Stage D's three new dispatcher rules
(every variant must carry `@PacketType`, value in 0..255, unique
within parent). JVM hot path is allocation-clean for `[B` per
Locked Decision row 16. Test tally: 77 cases green, 0 failures.

Stage E is the next capability gate. **Do not write code yet.**
Survey scope, lock the three deferred decisions that converge on
Stage E (`@LengthFrom` resolution form, `@WhenTrue` DSL surface,
field-path tracking mechanism), and propose a test-driven sequence
— the Stage D `STAGE_D_RESUME.md` was the model.

## What's already locked

`PHASE_9_RESET.md` Locked Decisions rows 1–17 are durable. The
load-bearing ones for Stage E:

- **Row 1** — `Codec<T>` interface union. Stage E's emitted code
  still produces `object MyMessageCodec : Codec<MyMessage>`;
  conditional/length-from logic is internal to the generated decode
  / encode / wireSize / peekFrameSize bodies, not a new interface
  shape.
- **Row 2** — `WireSize`: `Exact(bytes)` fast path, `BackPatch`
  default. Stage E's `@WhenTrue`-conditional fields and
  `@LengthFrom`-bounded fields force `BackPatch` whenever the
  presence/length of a field depends on another field's runtime
  value. Static-`Exact` is only available when every field's wire
  width is known from the type alone (the Stage A/B/C scalar shape).
  Decide at slice-1 whether the emitter even attempts to compute
  conditional `Exact` (probably not — once any field is conditional,
  collapse to `BackPatch` and stop).
- **Row 3** — `PeekResult`: `Complete(bytes)`, `NeedsMoreData`,
  `NoFraming`. Stage E's `peekFrameSize` is the harder path: the
  framework must peek through fixed-size scalar prefixes, decide
  conditional fields' presence by peeking the boolean source, then
  walk through length-from references the same way Stage C peeks a
  `@LengthPrefixed` prefix. When the boolean source or length source
  is itself derived (value class property, dotted path), the peek
  has to assemble the same value the decode would. If any source is
  past a variable-length field, peek returns `NoFraming`. The
  cleanest rule: peek follows the same field-evaluation order as
  decode, short-circuits to `NoFraming` the moment it can't reach a
  source statically.
- **Row 5** — Sync = `buffer.setLimit()`, async = `parent.slice(N)`.
  `@LengthFrom`-bounded fields use the sync `setLimit + restore`
  pattern (already proven by Stage A's `@LengthPrefixed
  @ProtocolMessage`); the inner decode reads exactly N bytes.
- **Row 6** — `CodecContext` shape: immutable map-backed, adds
  field-path tracking and direction-specific keys. Stage E's
  field-path tracking deferred decision (see below) probably lands
  as a new key on `DecodeContext` / `EncodeContext`, pushed/popped
  through nested codec calls so error messages can name the path
  (e.g., `"Connect.willTopic"` rather than `"willTopic"`).
- **Row 9** — Wire-mirroring carve-out: redundant length carriers
  use `@LengthPrefixed` on the bounded field, not `@LengthFrom` to
  an adjacent sibling. R1 already enforces this. Stage E's
  `@LengthFrom` is reserved for **non-adjacent** length carriers
  (length parsed elsewhere, several fields away, or carried by a
  parent via `@DispatchOn`).

## What Stage E must lock

Three deferred decisions on `PHASE_9_RESET.md`'s Deferred Decisions
table converge on Stage E. None of them is settled today — Stage E's
slice 1 should be a doctrine commit that locks all three (or at
minimum the ones that drive the emitter's slice-2 shape).

### 1. `@LengthFrom("fieldName")` resolution form

Sketch on the deferred-decisions table: "String DSL vs property
reference vs compile-time resolved name". Today's annotation
surface uses a String (`@LengthFrom(field = "payloadLength")`).
The processor already validates the *adjacent* case (R1), but
emits no codec when `@LengthFrom` appears, so resolution form has
never been exercised.

Three options with tradeoffs:

1. **String DSL with KSP validation** — keep `@LengthFrom("name")`,
   resolve at compile time against the constructor parameter list,
   error if the name doesn't exist or isn't a numeric type or comes
   *after* the bound field. Pros: zero runtime cost, simple
   annotation surface, matches the `@WhenTrue` DSL shape. Cons:
   refactoring renames don't propagate (but KSP catches dangling
   references).
2. **Property reference** — `@LengthFrom(MyMsg::payloadLength)`.
   Pros: refactor-safe. Cons: requires a complete annotation
   redesign (annotations can't take property references with full
   type-safety in Kotlin today; it'd have to be a `KProperty1<T, *>`
   parameter, which annotations don't support); breaks the parallel
   with `@WhenTrue`.
3. **Hybrid: keep String, add a build-time fixup that emits a
   typed companion** — overkill for the current scope.

Locked-leaning answer: option 1. The `@WhenTrue("flags.willFlag")`
shape on the same annotation surface uses the dotted-string DSL —
keeping `@LengthFrom` symmetrical means a single resolution helper
can serve both. KSP validation closes the refactor-safety gap.
Promote to Locked Decisions row 18 in slice E1.

### 2. `@WhenTrue("flags.willFlag")` DSL surface

Sketch: "Dotted-string DSL with KSP validation against actual field
path". The annotation kdoc already documents both forms:

```kotlin
@WhenTrue("hasExtra")        val extra: Int? = null         // sibling boolean
@WhenTrue("flags.willFlag")  val willTopic: String? = null  // value-class property
```

Two questions to settle:

(a) **What's the source kind universe?**
- Sibling `Boolean` field. Trivial: read the field's variable in
  the decoded local scope.
- Value-class property: `flags.willFlag` where `flags: Flags`
  (`@JvmInline value class`) and `willFlag: Boolean` is a `val`
  defined on `Flags`. The codec assembles `flags` first, then
  evaluates `flags.willFlag` like Kotlin would.
- Sibling property of a non-value-class type? Probably not — the
  simple "field path" model breaks down. Lock: source kinds are
  sibling `Boolean` field or `<siblingField>.<property>` where
  the sibling is a value class with a `Boolean`-returning
  property. Anything else is a compile error.

(b) **Where can `@WhenTrue` apply?**
- The annotation kdoc says "field must be nullable with a default
  value of `null`". KSP can enforce this. Lock: `@WhenTrue` field
  must be `T?` with `= null` default; emitter emits the field as
  `null` when the predicate is false.

Promote (a)+(b) as Locked Decisions row 19 in slice E1.

### 3. Field-path tracking mechanism

Sketch: "`PathContext` facet pushed/popped through nested codec
calls". Stage E's error messages need to name the field path
(`"Connect.willTopic"`, `"Connect.flags.willFlag"`) when a
`@WhenTrue` predicate references a path that doesn't exist or
when a `@LengthFrom`-bound decode underflows. Three options:

1. **`CodecContext` key holding a path stack.** Generated decode
   pushes the field name on entry, pops on exit. Pros: composes
   with the existing `CodecContext` immutable-map shape. Cons:
   immutable-map push/pop allocates a new map per field, which
   collides with the zero-`[B` allocation contract on the JVM hot
   path. Mitigation: use a typed thread-local-like facet or pass
   the path as a function parameter.
2. **Path passed as a function parameter on the generated emit
   helpers** — but `Decoder.decode` / `Encoder.encode` interface
   signatures are fixed.
3. **Reserve path tracking for *exception construction time only*:
   the generated code passes literal field names as the
   `fieldPath` constructor arg (Stage A/B/C/D already do this).
   No runtime path stack at all.**

Locked-leaning answer: option 3 plus a small extension. Stage A–D's
exception construction passes `fieldPath = "<OwnerSimpleName>.<fieldName>"`
as a string literal at the throw site — zero runtime allocation,
KotlinPoet-friendly. Stage E continues this pattern: when a
`@WhenTrue` predicate's value-class property is invalid, the throw
site builds `"<Owner>.<field>.<property>"` literally. The
"PathContext facet" sketched on the deferred-decisions table is
unnecessary if we never need to attach a path to a *runtime-thrown*
exception from a deeper codec — and we don't, because field paths
are statically known at emit time. Promote as Locked Decisions
row 20 in slice E1.

## Stage E scope (from `PHASE_9_RESET.md`)

> ### Stage E — `@LengthFrom` + `@WhenTrue` conditional fields
>
> - **Vector:** MQTT v3 CONNECT — flags byte drives optional will,
>   username, password fields, each `@LengthPrefixed` UTF-8.
> - **Capability:** cross-field length references via
>   `@LengthFrom("siblingField")`; boolean-DSL conditional inclusion via
>   `@WhenTrue("flags.willFlag")`; processor enforces that any non-terminal
>   variable-length field has a length source.
> - **Acceptance:** round-trip across every combination of optional flags;
>   missing length source on a non-terminal variable field is a compile
>   error; bad path in `@WhenTrue` is a compile error with field-path in
>   the message.

The MQTT v3 CONNECT shape (per the existing
`MqttConnectProtocolName.kt` Stage C reference and the `Annotations.kt`
docs):

```kotlin
@JvmInline @ProtocolMessage
value class ConnectFlags(val raw: UByte) {
    val cleanSession: Boolean get() = (raw.toInt() and 0x02) != 0
    val willFlag: Boolean      get() = (raw.toInt() and 0x04) != 0
    val willQos: Int           get() = (raw.toInt() ushr 3) and 0x03
    val willRetain: Boolean    get() = (raw.toInt() and 0x20) != 0
    val passwordFlag: Boolean  get() = (raw.toInt() and 0x40) != 0
    val usernameFlag: Boolean  get() = (raw.toInt() and 0x80) != 0
}

@ProtocolMessage
data class MqttConnect(
    @LengthPrefixed val protocolName: String,            // "MQIsdp" or "MQTT"
    val protocolLevel: UByte,                             // 3 or 4
    val flags: ConnectFlags,
    val keepAlive: UShort,
    @LengthPrefixed val clientId: String,
    @WhenTrue("flags.willFlag")    @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("flags.willFlag")    @LengthPrefixed val willMessage: String? = null,
    @WhenTrue("flags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("flags.passwordFlag") @LengthPrefixed val password: String? = null,
)
```

Note: every conditional field is also `@LengthPrefixed`, so the
length is self-carried — `@LengthFrom` does **not** appear in the
MQTT v3 CONNECT vector. To exercise `@LengthFrom`, a sibling vector
is needed.

A second vector for `@LengthFrom`:

- **Custom byte-counted payload** — `data class RemoteHeader(val
  payloadLength: UShort, val flags: UByte, val correlationId: UInt,
  @LengthFrom("payloadLength") val payload: String)`. Already shown
  in the `@LengthFrom` kdoc. Non-adjacent (length carrier is two
  fields ahead of the bound field), terminal `String` body. Slice 4
  or 5.

## Suggested test-driven sequence

(Don't take this as final — propose your own after surveying.)

1. **Doctrine commit — locks rows 18, 19, 20 in PHASE_9_RESET.md.**
   Single docs-only commit, no emitter changes. Settles `@LengthFrom`
   as String-DSL with KSP validation, `@WhenTrue` source kinds and
   placement rules, and the no-runtime-path-stack rule for field-
   path attribution. Mirrors Stage D slice 1.
2. **Smallest emitter slice — `@WhenTrue` against a sibling Boolean.**
   Vector: a small `data class WithOptional(val hasExtra: Boolean,
   @WhenTrue("hasExtra") val extra: Int? = null)`. Emitter changes:
   detect `@WhenTrue` on a constructor parameter, resolve the path
   (sibling `Boolean` only, value-class form deferred to slice 4),
   emit `if (hasExtra) buffer.readInt() else null`. `wireSize`
   collapses to `BackPatch` (the field's presence is runtime-decided).
   `peekFrameSize` peeks the sibling boolean, decides, walks. Tests:
   round-trip `hasExtra=true` and `hasExtra=false`, peek drip-feed.
3. **`@WhenTrue` against a value-class property.**
   Extend slice 2's resolver to handle `"flags.willFlag"`. Vector:
   add `flags: ConnectFlags` and `@WhenTrue("flags.willFlag")
   willTopic: String?` to a small fixture (not yet the full MQTT
   CONNECT). Validator: bad path (`flags.nonexistent`) is a compile
   error naming the offending parameter and the available
   properties on the resolved type. Field-path tracking via literal
   string at the throw site (row 20).
4. **`@LengthFrom` against a non-adjacent sibling.**
   Vector: the `RemoteHeader` shape from the kdoc. Emitter changes:
   detect `@LengthFrom("name")` on a terminal `String`, resolve the
   sibling at compile time (must come before the bound field, must
   be a numeric type, must be non-adjacent — R1 already errors on
   adjacent), emit the bounded read pattern (`val sliceLimit =
   buffer.position() + name.toInt(); buffer.setLimit(sliceLimit);
   try { buffer.readString(name.toInt(), Charset.UTF8) } finally {
   buffer.setLimit(outerLimit) }`). Validators: missing length
   source on a non-terminal variable field is a compile error.
5. **Real-spec fixture — full MQTT v3 CONNECT.** Compose every
   slice 2/3/4 capability. Round-trip vectors covering every
   combination of `willFlag`, `usernameFlag`, `passwordFlag`. Plus
   the connect-with-empty-clientId and connect-with-multibyte-UTF-8
   client-id edge cases. The encode side must agree with the decode
   side: when `flags.willFlag = false` and `willTopic = null`, the
   encoder writes nothing for that slot.
6. **Compile-error tests** — `@WhenTrue` on a non-nullable field;
   `@WhenTrue` with no default `= null`; `@WhenTrue` referencing a
   field that doesn't exist; `@WhenTrue` referencing a non-Boolean
   type; `@LengthFrom` referencing a field after the bound field;
   `@LengthFrom` on a non-numeric source field.
7. **Allocation tracker extension** — point a new
   `MqttConnectAllocationTest` at the dispatcher (encode + decode
   of a fully-populated CONNECT) to confirm `@WhenTrue`/`@LengthFrom`
   don't introduce per-call `[B` allocations.
8. **Full check** — `:buffer-codec-processor:test
   :buffer-codec-test:jvmTest` green; new fixture and validator
   tests pass; existing 77 cases regression-free.

Open questions to address while sequencing:

- Does `peekFrameSize` need to handle a `@WhenTrue` whose source is
  itself past a variable-length field? E.g., `@LengthPrefixed val
  payload: String` followed by `@WhenTrue("hasExtra") val extra:
  Int?` where `hasExtra` is *after* `payload`. In that case the
  peek can't reach the boolean source statically — return
  `NoFraming`. Is that case relevant for any real protocol? MQTT
  CONNECT's flags byte is always fourth (after protocol name +
  protocol level), well before any variable-length field. Probably
  no action needed beyond a silent-skip + `NoFraming` fallback;
  document the boundary in row 19.
- `@LengthFrom` widens the field-type universe (`String` is the
  obvious one; future stages may also want `ByteArray`-via-Payload,
  nested `@ProtocolMessage` data class). Stage E should pick the
  minimum: `String` only, for parity with the `@LengthPrefixed val:
  String` Stage C terminal. Other types defer to later stages.
- The encoder's `@WhenTrue` skip semantics: when the predicate is
  false, write zero bytes for that slot. When true, write the
  field's bytes (including its `@LengthPrefixed` prefix). The user
  is responsible for keeping the boolean source consistent with
  the field's nullability — if `flags.willFlag = true` but
  `willTopic = null`, that's an `EncodeException` at runtime.
  Document this in the validator's diagnostic.

## Carryovers (still in force)

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- mavenLocal republish stays deferred until after Stage H.
- Apple build verification carryover from commits `f0a68a9` /
  `318b638` (touched `MutableDataBuffer` paths) — confirm on a macOS
  host before any merge to main. Not blocking Stage E.
- Android `:buffer:compileReleaseKotlinAndroid` was broken at the
  start of the Stage D closeout (signature drift on
  `AndroidDeterministicUnsafeJvmBuffer.sliceImpl`); fixed in
  out-of-stage commit `fbee95e`. No further action.
- Eight `*_BUG.md` / `*_ISSUES.md` notes at repo root remain out of
  scope. Don't sweep them.
- Pre-existing ktlint violations in
  `buffer-codec-processor/src/main/.../ProtocolMessageProcessor.kt`
  (Stage B), `DnsHeaderCodecTest.kt`, `FlvTagHeaderCodecTest.kt`
  (Stage B), and `BufferSliceByteOrderBehaviorTests.kt` (unrelated
  buffer-runtime test) are tech debt, not Stage E blockers. Don't
  sweep unless asked.
- §8 / R1 / R3 / R4 / Stage D dispatcher validators stay. Stage E
  adds the `@WhenTrue` / `@LengthFrom` validators alongside, not in
  place of them.
- WASM/`nonJvm` `writeString` allocates one internal `byte[]` per
  call — Locked Decision row 16 documents this as a runtime-side
  follow-up, not a codec-emitter regression. Stage E inherits the
  same JVM-only zero-`[B` claim.
- **Stage-H follow-up** (deferred bundle: MQTT `CorrelationData` /
  `AuthenticationData` migration to 2-field shape, R1 Payload
  exclusion removal, R3 widening to `@Payload` type parameters,
  `mqttPropertySize` deletion) is its own work. Stage E should *not*
  preempt Stage H.

## Read these to load context

- `buffer/CLAUDE.md` — `BufferFactory` discipline, wrapper
  transparency, codec-using-protocol-codecs guidance, sealed
  dispatch patterns, `peekFrameSize` contract, `CodecContext`
  semantics.
- `PHASE_9_RESET.md` — Locked Decisions rows 1–17, Stages A–H plan,
  Deferred Decisions table.
- `PHASE_10_DESIGN_NOTES.md` — derivation history; for Stage E look
  for the `@LengthFrom` / `@WhenTrue` / field-path sections.
- `buffer-codec-test/.../simple/Command.kt` and the new
  `CommandCodecTest.kt` — Stage D reference fixture; Stage E
  variants compose with the dispatcher in later real-spec work
  (Stage F).
- `buffer-codec-test/.../mqtt/MqttConnectProtocolName.kt` — Stage C
  reference for the MQTT-CONNECT-style protocol-name field. The
  full CONNECT vector in slice 5 builds on top of this.
- `buffer-codec/.../Annotations.kt` — annotation surface; Stage E
  exercises `@WhenTrue` (`expression: String`) and `@LengthFrom`
  (`field: String`). The kdocs already document the intended
  semantics; lock them on row 19/18 then make the emitter match.
- `buffer-codec-processor/.../CodecEmitter.kt` — the Stages A + B +
  C + D emitter (now with two emit paths: data class and sealed
  dispatcher). Stage E adds field-conditional logic to the data-
  class emit path; the sealed dispatcher is unaffected.
- `buffer-codec-processor/.../ProtocolMessageProcessor.kt` —
  validators (§8, R1, R3, R4, Stage D rules). Stage E adds:
  (a) `@WhenTrue` field must be `T?` with `= null` default;
  (b) `@WhenTrue` path must resolve to a sibling `Boolean` or
  `<sibling>.<property>` where sibling is a value class and
  property returns `Boolean`;
  (c) `@LengthFrom("name")` must resolve to a numeric sibling
  declared *before* the bound field (R1 already errors on
  adjacent);
  (d) any non-terminal variable-length field without `@WhenTrue`
  or a length source is a compile error.
- `buffer-codec-test/src/jvmTest/.../alloc/JfrAllocationTracker.kt`
  — the allocation tracker; reuse for slice-7 regression test
  alongside `SimpleHeaderAllocationTest` and `CommandAllocationTest`.

## Five most recent commits (local)

```
e3b0f37 Stage D slice 5: JFR allocation regression for sealed dispatcher
f237c39 Stage D slice 3: KSP negative-case tests for sealed dispatcher
e16d10d Stage D slice 2: emit @PacketType sealed dispatcher + Command vector
6016aea Stage D slice 1: lock unknown-discriminator behaviour (Locked Decision row 17)
761ae3c docs: add STAGE_D_RESUME.md briefing for next session
```

## When this file's job is done

Delete `STAGE_E_RESUME.md` in the same commit that closes the first
Stage E slice (whichever fixture is emitted first — likely the
single-`@WhenTrue` slice-2 fixture). The locked decisions absorbed
in Stage E (rows 18, 19, 20) get added to `PHASE_9_RESET.md` as new
Locked Decisions rows; this ephemeral hand-off has no further role.
