# Codec schema-drift checking — a consumer-facing wire-compat gate

> Design + reference for the shipped schema-drift wire-compat gate (`buffer-codec-schema`
> module + `buffer-codec-gradle-plugin`, landed in #195). Documents the motivation, the
> descriptor format, and the drift-classification rules. Source comments across those
> modules point here for the "why".

## Motivation

`@ProtocolMessage` encodes **positionally** — fields ride the wire in constructor
order, enums ride as their `ordinal`, sealed variants as their `@PacketType` /
`@DispatchValue` discriminator. The library hands authors the *primitives* to design
forward-compatible messages (`@EnumDefault`, `@ForwardCompatible` / `@UnknownVariant`,
`@FramedBy`), but nothing **inspects** a protocol for breakage. The contract is enforced
by doc comments and discipline:

> *"Enums are append-only on the wire — add entries at the end, never reorder."*
> — `@EnumDefault` KDoc, `Annotations.kt`

The failure modes are all **silent**, and round-trip tests do not catch any of them
(you encode and decode with the same new code, so it passes against *different but still
valid* output — the exact trap that motivated the codec-source snapshot, see
`buffer-codec-test/codec-snapshots/README.md`):

- **Reorder enum entries** → `ordinal→meaning` swaps; peers already on the wire now
  misread every value.
- **Insert / delete a field** → every later field shifts; framing breaks for old peers.
- **Change a field's wire width or byte order** (`@WireBytes`, `@WireOrder`) → silent
  corruption.
- **Reassign a `@PacketType` / `@DispatchValue`** → variant dispatch breaks.

The existing **codec-source snapshot** (`codec-snapshots/` + `CodecSnapshotTest`) does
**not** cover this. It diffs generated *`.kt` source* to catch codegen regressions in
the library itself; it is hardcoded to this repo's fixtures and build dirs, lives in the
**unpublished** `buffer-codec-test` module, and a reordered enum regenerates a
*consistent* codec — the snapshot just gets a new baseline. It guards codegen shape;
this feature guards **wire semantics**. They are orthogonal and both stay.

This is a **consumer-facing** capability. A downstream user who annotates their own
types today gets zero drift protection and no diffable artifact to baseline. This spec
ships them one.

## Scope

Two deliverables:

1. **Schema descriptor** — KSP emits a stable, line-oriented descriptor of every
   `@ProtocolMessage` type's wire-significant shape, as a build output the consumer can
   baseline into source control. *(processor work)*
2. **Gradle plugin** (`buffer-codec-gradle-plugin`, published) — `checkCodecSchema` /
   `updateCodecSchema` tasks that baseline the descriptor and classify drift. *(new module)*

Settled decisions (from design discussion):

- **Delivery = Gradle plugin + descriptor** (turnkey for consumers), not a doc-only
  test pattern.
- **Enforcement = warn by default, opt-in fail.** A breaking delta prints a structured
  warning; `failOnBreaking = true` escalates to a build failure. Adoption never blocks
  a build out of the box.
- The library **dogfoods** its own plugin on `buffer-codec-test`'s fixtures with
  `failOnBreaking = true` — closing the library's own semantic-drift gap and
  continuously exercising the plugin.

## The descriptor format

One aggregate `codec-schema.txt`, emitted alongside the generated codecs. Stable sort
(package + type name), line-oriented so diffs are reviewable, append-friendly so adding
at the end produces a clean trailing diff. Each record is keyed by **position /
discriminator**, never by source-declaration order in the file.

```
enum com.acme.proto.Intensity default=Normal
  0 Normal
  1 Bold
  2 Faint
message com.acme.proto.Login wireOrder=Big
  0 id            scalar:i32 order=Big
  1 type          scalar:u16 order=Big
  2 payload       string len-prefixed:u16/Big
  3 ttl?          when(sibling:hasTtl) scalar:u32 order=Big
sealed com.acme.proto.Op dispatchOn=OpCode framedBy=OpLengthCodec forwardCompatible=Op.Unknown
  0x12 Scroll
  0x13 Resize
```

Each line carries exactly what makes a change wire-breaking — and nothing cosmetic:

| Record | Key (stable identity) | Wire-significant payload |
|--------|----------------------|--------------------------|
| `enum` | ordinal | entry name, `@EnumDefault` marker |
| `message` field | position index | type/kind, wire width, byte order, framing (len-prefix width+order, `@LengthFrom` source, `@RemainingBytes`), `@When` predicate |
| `sealed` variant | dispatch value (`@PacketType`/`@DispatchValue`) | variant simple name, `@DispatchOn` discriminator kind+width, `@FramedBy`, `@ForwardCompatible` sink |

**Names are recorded but advisory.** The wire never carries a field name or enum entry
name, so a pure rename (same position / same ordinal / same wire shape) is *safe*. The
differ cannot distinguish "renamed entry at ordinal 1" from "put a different entry at
ordinal 1" — both look like `ordinal 1: Bold → Strong`. Per the warn-default policy this
is correct: **warn on any `ordinal→name` or `position→name` change**, and let the human
confirm "just a rename." Escalating to fail is the author's opt-in once they trust their
own discipline.

## Drift classification

The plugin parses the freshly-generated descriptor and the baselined one, then
classifies each delta:

**Safe (silent, always passes):**
- Append an enum entry at the end (new highest ordinal).
- Append a message field at the end (new highest position).
- Add a sealed variant with a previously-unused dispatch value.
- Add `@EnumDefault` to an enum that lacked one (widens forward-compat).

**Breaking (warn → fail under `failOnBreaking`):**
- An existing ordinal maps to a different entry name (reorder / insert / delete).
- An existing field position changes type, kind, wire width, byte order, or framing.
- An existing field position's name changes (advisory — flagged for human confirm).
- A `@PacketType` / `@DispatchValue` is reassigned to a different variant, or removed.
- `@EnumDefault` removed (narrows a forward-compat sink to a strict throw).
- A field or enum entry is removed (later positions/ordinals shift).
- Discriminator kind/width or `@FramedBy` codec changes on a sealed parent.

The warning is structured and actionable, e.g.:

```
codec schema drift (breaking): com.acme.proto.Intensity
  ordinal 1 was 'Bold', now 'Faint' — reordering changes the meaning of bytes
  already on the wire. Append new entries at the end instead.
  (set codecSchema.failOnBreaking = true to make this fail the build)
```

## Gradle plugin surface

New published module `buffer-codec-gradle-plugin` (sibling of `buffer-codec` /
`buffer-codec-processor` in `settings.gradle.kts`, with `maven-publish` + signing —
mirror `buffer-codec`'s `build.gradle.kts`). Consumer applies:

```kotlin
plugins { id("com.ditchoom.buffer.codec-schema") }

codecSchema {
    baseline.set(file("src/codecSchema/codec-schema.txt"))
    failOnBreaking.set(false)   // default — warn only
}
```

Tasks:

- **`updateCodecSchema`** — copies the freshly-generated descriptor over the baseline.
  The deliberate "I meant to change this" gesture (the analogue of accepting a snapshot
  via `-Dupdate.snapshots=true`). Run after an intentional, reviewed schema change.
- **`checkCodecSchema`** — wired into `check` (`dependsOn` from the `check` lifecycle
  task). Locates the generated descriptor in the KSP output dir, diffs against the
  baseline, classifies, and warns / fails. No baseline yet → emits the descriptor and
  prints "baseline created, commit `src/codecSchema/codec-schema.txt`."

The plugin must locate the descriptor across KMP source sets — the processor emits into
`build/generated/ksp/metadata/commonMain/...` for common types (matching where codecs
land today). Resolve the path from the KSP task output rather than hardcoding, so it
survives source-set layout changes.

## Processor work (emit the descriptor)

The descriptor is a **pure projection of the IR** that the analyzer already produces —
`CodecShape` (`CodecIr.kt:288`), `DispatchShape` (`:120`), and every `FieldSpec`
(`:458`). Add a descriptor emitter that runs after analysis and writes one record per
shape. Wire it into the existing emit pass in `CodecEmitter.kt`.

**Correctness bar — total coverage.** A descriptor that misses a wire-significant
attribute is worse than none: it greenlights a breaking change. Enforce this
structurally:

- The field-record builder must be an **exhaustive `when` over `FieldSpec`** (it is a
  `sealed interface`, so a new variant fails to compile until someone decides its
  descriptor line). Same for `ConditionalInner`, `Discriminator`, and `LengthSource`.
- Every attribute that changes bytes must appear in the record: `Scalar.kind` +
  `resolvedWireOrder` + `wireBytes`; `ValueClassScalar.innerKind` + `valueClassWireOrder`
  + `wireBytes`; all `prefixWidth`/`prefixWireOrder` pairs; `UseCodecScalar.isBounding` +
  `isVariableLength`; `reservedTrailingBytes`; the `@When` predicate; the dispatch
  discriminator kind/width and `@FramedBy` codec.
- Add a processor test asserting **every `FieldSpec` member contributes a non-empty
  descriptor line** (reflective or fixture-driven), so the exhaustiveness can't be
  satisfied with a silent `else -> ""`.

**One required IR extension.** `FieldSpec.EnumScalar` (`CodecIr.kt:504`) carries
`entryCount` and `defaultEntryName` but **not the ordered entry names** — and the
`ordinal→name` mapping is exactly what enum-reorder detection needs. Extend `EnumScalar`
(and the `analyzeEnumField` path in `CodecAnalyzer.kt`) to capture
`entryNames: List<String>` in declaration order. Without it the descriptor can only see
the count, and a reorder of same-count enums is invisible.

## Test obligations

- Descriptor is **deterministic** across runs (stable sort; no map-iteration-order
  leakage) — same sources produce byte-identical `codec-schema.txt`.
- Every `FieldSpec` / `ConditionalInner` / `Discriminator` / `LengthSource` member emits
  a distinct, wire-complete line (coverage test above).
- Classifier unit tests for each row of the safe/breaking tables — especially: append
  enum entry (safe), reorder enum entries (breaking), insert mid-message field
  (breaking), widen `@WireBytes` (breaking), flip `@WireOrder` (breaking), reassign
  `@PacketType` (breaking), remove `@EnumDefault` (breaking), rename field (breaking,
  advisory).
- Plugin integration: no-baseline creates one; matching schema passes; breaking delta
  warns under default and fails under `failOnBreaking = true`; `updateCodecSchema`
  rewrites the baseline.
- Dogfood: `buffer-codec-test` applies the plugin with `failOnBreaking = true` over its
  protocol fixtures, with a committed baseline.

## Relationship to existing features

- **`@EnumDefault` / `@ForwardCompatible` / `@UnknownVariant`** design messages to
  *tolerate* unknown values at runtime. This feature detects, at build time, when the
  author has *broken* the schema for peers that lack those tolerances. Complementary,
  not overlapping.
- **Codec-source snapshot** (`CodecSnapshotTest`) guards codegen emit-shape; **schema
  drift** guards wire semantics. Keep both.

## Tracked separately

- **Per-type baselines / multiple protocols in one module** — the aggregate
  `codec-schema.txt` is the v1. If a consumer wants per-protocol files or selective
  baselining, that is a follow-on to the plugin's file resolution; out of scope here.
- **A machine-readable descriptor (JSON / `.proto` export)** for cross-language
  consumers — the v1 format is human-diffable text tuned for review, not interchange.
  An export format is a separate concern.

## Implementation (shipped in #195)

The feature is fully implemented and tested. This section maps the design above to the
source that realizes it.

**Pure-logic core (emit → parse → classify), unit-tested without Gradle:**

- **IR:** `FieldSpec.EnumScalar.entryNames: List<String>` (declaration order == ordinal),
  populated in `analyzeEnumField` (`CodecIr.kt`, `CodecAnalyzer.kt`).
- **Emitter + model:** `CodecSchemaModel.kt` holds `SchemaRecord` (Enum/Message/Sealed) — the
  **single format authority**. `CodecSchemaDescriptor.kt` projects IR → records (exhaustive
  `when` over `FieldSpec`/`ConditionalInner`/`Discriminator`/`LengthSource`/`PayloadCodecSource`).
  Wired via `CodecEmitter.writeSchemaDescriptor()` ← `ProtocolMessageProcessor.finish()`, which
  emits one aggregate `codec-schema.txt` (`aggregating = true`) alongside the codecs.
- **Parser:** `CodecSchemaParser.kt` — inverse of emit. `renderSchemaRecords(parse(text)) == text`
  is a locked round-trip (dogfood both ways).
- **Classifier:** `CodecSchemaClassifier.kt` — `classify(baseline, current): List<SchemaDrift>`,
  three severities `SAFE`/`ADVISORY`/`BREAKING`, deterministic type-sorted output.
- **Tests:** `CodecSchemaDescriptorTest`, `CodecSchemaDescriptorCodegenTest`,
  `CodecSchemaParserTest`, `CodecSchemaClassifierTest`. `kotlin("reflect")` added as
  `testImplementation` (reflective leaf-coverage check).

**Format decisions finalized (differ from the illustrative examples above — these are
authoritative):**

- Records: `enum <fqcn> [default=<name>]` / `message <fqcn>` /
  `sealed <fqcn> dispatch=<tok> [framedBy=<tok>] [forwardCompatible=<tok>]`; entries are
  two-space-indented, keyed by ordinal / position / dispatch-value.
- **Every structural token is space-free** (the discriminator is a single comma-joined token,
  e.g. `valueclass:p.Tag/2B,inner=UShort/Big,dispatchValue=type:Int`). The **only** multi-token
  field is a message field's opaque `descriptor`. So headers tokenize by `split(' ')`.
- No message-level `wireOrder=` token — `CodecShape` has no such field; per-field `order=` is
  authoritative.
- **Names are advisory and classified by record kind:** a *pure* rename → `ADVISORY` (warns,
  never fails, even under `failOnBreaking`); a reorder/insert/swap/reassign (old name reappears
  at a different key) → `BREAKING`. Message-field renames with an unchanged descriptor are always
  `ADVISORY` (the descriptor independently carries the wire shape). R8/ProGuard is irrelevant —
  the descriptor is generated from source at KSP time and baselined in VCS.

**Done — step 3, the Gradle plugin and its shared core:**

- **Reuse resolved via a shared module.** The model/parser/classifier moved to a new published
  JVM module **`buffer-codec-schema`** (package `com.ditchoom.buffer.codec.schema`, public API) —
  *not* a dependency on the processor artifact, which would drag KSP + kotlinpoet + the KMP
  `buffer-codec` jar onto a Gradle plugin's classpath. Both `buffer-codec-processor` (emitter) and
  `buffer-codec-gradle-plugin` (differ) depend on it, so the single-format-authority guarantee
  holds across the module boundary. `CodecSchemaDescriptor` stays in the processor (it needs IR);
  all four schema tests stay in the processor (the parser/descriptor tests drive the real emitter).
- **`buffer-codec-gradle-plugin`** (published, `java-gradle-plugin` + `maven-publish` + signing;
  plugin id `com.ditchoom.buffer.codec-schema`). `CodecSchemaExtension` (`baseline` defaulting to
  `src/codecSchema/codec-schema.txt`, `failOnBreaking` defaulting to `false`). `checkCodecSchema`
  (wired into `check` via `LifecycleBasePlugin`; `dependsOn` all `ksp*` tasks; locates
  `codec-schema.txt` by scanning the `build/generated/ksp` root — no hardcoded source-set subpath —
  and dedupes copies by content; no baseline → writes it + warns "baseline created … commit";
  classifies; warns on `ADVISORY`/`BREAKING`; fails on `BREAKING` under `failOnBreaking`).
  `updateCodecSchema` (copies generated over baseline). All three task types are
  `@DisableCachingByDefault`. Tested with Gradle TestKit (`CodecSchemaPluginTest`, 7 cases) against
  a pre-staged descriptor — no full KMP+KSP compile needed (the emit chain is covered by
  `CodecSchemaDescriptorCodegenTest`).

**Done — step 4, dogfood (with a Gradle-9 deviation from the literal spec):**

The spec called for `buffer-codec-test` to *apply the plugin* with `failOnBreaking = true`. That is
**not possible in this monorepo under Gradle 9**: applying a sibling subproject as a plugin requires
putting it on the consumer's buildscript classpath, and Gradle 9 rejects buildscript *project*
dependencies ("Project dependencies cannot be declared here"). The escape hatches both have costs
(a composite/`includeBuild` restructure pulls the two modules out of the main `settings.gradle.kts`
and forces a publishing-pipeline change; mavenLocal makes the repo unbuildable from a clean
checkout). **Consumers are unaffected** — they apply the published plugin via `plugins { id(...) }`
from Maven Central, which has no buildscript-classpath problem.

So the library dogfoods the **same pure-logic core** (`CodecSchemaParser` + `CodecSchemaClassifier`
from `buffer-codec-schema`) as a `jvmTest` instead:
`buffer-codec-test/src/jvmTest/.../schema/CodecSchemaDriftGateTest.kt`. It compares the generated
descriptor (`build/generated/ksp/metadata/commonMain/resources/codec-schema.txt`) against the
committed baseline (`buffer-codec-test/src/codecSchema/codec-schema.txt`, ~1188 lines: 2 enums,
311 messages, 36 sealed), fails the build on `BREAKING`, warns on `ADVISORY`, passes `SAFE`. Regen:
`./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true` (shares the snapshot regen flag).
`buffer-codec-schema` was dropped to Java-8 bytecode so the JVM_1_8 test target can consume it.
The plugin's own task wiring / descriptor location is covered by `buffer-codec-gradle-plugin`'s
TestKit suite, so nothing about the consumer-facing plugin goes unexercised. Verified: matching
baseline passes, a reordered dispatch value fails the build, full `:buffer-codec-test:jvmTest` green.

Root `allTests` / `buildAll` now include `:buffer-codec-schema` and `:buffer-codec-gradle-plugin`.
