# Dispatch-path unification plan

Collapse the two sealed-dispatch codegen paths into a single dispatch IR
parameterized by a `Discriminator` sum type, executed as a byte-identical,
snapshot-gated staged refactor. This is the gateway to an HTTP/3 QUIC-varint
discriminator.

## Status (resume point)

Branch `codec/dispatch-wirewidth-track`. All stages byte-identical (305 goldens
unchanged) and committed:

- **Stages 0–2 done** — unified IR + adapters (`8f764f64`). All optional/variant
  dimensions are sum types (no nullables).
- **Stage 3 done** — decode (`d7538f43`)
- **Stage 4 done** — encode (`928eb015`)
- **Stage 5 done** — wireSize (`d4147d9b`)
- **Stage 6 done** — peek (`80db2b74`)
- **Stage 7 done** — file-shell collapsed to one `buildDispatchFileSpec`; framed
  encode/peek + aggregator migrated onto `DispatchShape` (`fad87fe0`)
- **Stage 8 done** — both analyzers (`analyzeSealedDispatcher` /
  `analyzeDispatchOnSealedDispatcher`) now return `DispatchShape` directly; the
  legacy `DispatcherShape` / `DispatchOnDispatcherShape` / `DispatchOnVariantSpec`
  / `VariantSpec` and their `toDispatchShape()` / `toDispatchVariant()` adapters
  are deleted. Byte-identical (305 goldens unchanged). (`4ec0d5aa`)
- **Stage 9 done** — both analyzers now return `DispatchAnalysisResult`
  (Supported | Rejected | NotApplicable), the dispatcher mirror of
  `AnalysisResult`. Each former `?: return null` is classified explicitly:
  validator-paired / fall-through / variant-self-reported cases → `NotApplicable`
  (stay silent, no double-report); the two true silent gaps → `Rejected` (loud):
  (1) a non-`data`/non-`object` variant under a simple `@PacketType` parent
  (`validateSealedDispatcher` never checks variant class kind), and (2) a
  non-peekable `@DispatchOn` discriminator inner kind — signed multi-byte
  (Short/Long), Int, or ULong (`validateDispatchOnSealed` accepts any numeric
  inner). `tryEmit` forwards `Rejected` diagnostics to `logger.error`. Existing
  goldens unchanged; two negative tests added to `EmitterDiagnosticsValidatorTest`
  (#16, #17).

All dispatch stages complete. Both analyzers feed ONE builder set on
`DispatchShape` and model their outcomes as an explicit sum type.

**Next: varint/H3** — a new `Discriminator.Varint` case (see §"How varint slots
in afterward"). The peek min-bytes guard becomes the one runtime-measured branch.

## The key insight

A simple `@PacketType` dispatcher **is** a `@DispatchOn` dispatcher whose
discriminator is a single fixed byte with *consumed-by-dispatcher* ownership and
*hex* label formatting. Unify by making the discriminator a sum type and the
divergent behaviors explicit properties.

## Two paths today

- **Simple `@PacketType`** — `DispatcherShape` / `analyzeSealedDispatcher` /
  `buildSealedDispatcherFileSpec` / `buildDispatcher{Decode,Encode,WireSize,Peek}Fun`.
- **`@DispatchOn`** — `DispatchOnDispatcherShape` /
  `analyzeDispatchOnSealedDispatcher` / `buildDispatchOnDispatcherFileSpec` /
  `buildGenericDispatchOnDispatcherTypeSpec` / `buildDispatchOn*Fun` + aggregator
  companion + forward-compatible arms.

Generic payload, `@FramedBy` dispatch, and `@ForwardCompatible` exist **only** on
the `@DispatchOn` path — the drift the unify-dispatch backlog rows describe.

## The two essential differences (everything else is cosmetic-but-frozen)

1. **Ownership.** Simple path *consumes* the discriminator byte in the dispatcher
   (`buffer.readUByte().toInt()`), then delegates to a variant whose body starts
   *after* the byte. `@DispatchOn` *peeks-and-rewinds*
   (`<DiscCodec>.decode(...)` then `buffer.position(discriminatorPosition)`) and
   the variant *re-reads* the discriminator as its first value-class-scalar field.
2. **Discriminator-counted-once.** Because of (1), `wireSize`/`peekFrameSize` add
   `1 +` in the simple dispatcher (the variant body excludes the byte) but the
   `@DispatchOn` dispatcher pure-delegates (the variant already counts its
   re-read discriminator). Evidence: `CommandCodec.kt:45` `Exact(9)` (8 body + 1
   disc) vs `TcpSegmentByFlagsSynCodec.kt:27` `Exact(1)`.

Frozen-for-byte-identity (cosmetic but golden-visible): `when`-label radix (hex
`0x01` vs decimal `1`), expected-set format, local var names (`discriminator` vs
`__discriminator`/`__dispatchValue`), and large-decimal underscoring (`2_048`).

## Unified IR (in CodecIr.kt) — as built (stages 1–2)

Per the no-nullable principle, every optional/variant dimension is an explicit
sum type, not a nullable field. `Discriminator` exposes `ownership`,
`labelFormat`, and `wireWidth` (computed). Feature dimensions are
`Genericity` (Monomorphic | Generic), `Framing` (Unframed | Framed),
`ForwardCompat` (Disabled | Enabled), and `CodecVisibility` (Public | Internal).
A variant's codec reference is `VariantCodecRef` (StaticObject | GenericInstance),
and a `ReReadByVariant` variant's dispatcher-side size is the explicit
`VariantWireSize.Delegated` rather than an ignored value.

```kotlin
internal data class DispatchShape(
    val packageName: String, val parentClassName: ClassName,
    val parentSimpleName: String, val codecSimpleName: String,
    val discriminator: Discriminator,
    val variants: List<DispatchVariant>,
    val genericity: Genericity,        // Monomorphic | Generic(binding)
    val framing: Framing,              // Unframed | Framed(config)
    val forwardCompat: ForwardCompat,  // Disabled | Enabled(config)
    val visibility: CodecVisibility,   // Public | Internal
)

internal data class DispatchVariant(
    val simpleName: String, val className: ClassName, val codecClassName: ClassName,
    val dispatchValue: Int,
    val codecRef: VariantCodecRef,     // StaticObject | GenericInstance(fieldName)
    val wireSize: VariantWireSize,     // Delegated for ReReadByVariant
)

internal sealed interface Discriminator {           // ownership/labelFormat/wireWidth
    data object FixedByte : Discriminator            // Hex, ConsumedByDispatcher, Fixed(1)
    data class ValueClass(className, codecClassName, innerKind, innerWireOrder,
        dispatchValueProperty, dispatchValueKind) : Discriminator  // Decimal, ReReadByVariant
    data class Varint(dispatchValueProperty, dispatchValueKind) : Discriminator // Variable, reserved
}
internal enum class DiscriminatorOwnership { ConsumedByDispatcher, ReReadByVariant }
internal enum class LabelFormat { Hex, Decimal }
```

Adapters `DispatcherShape.toDispatchShape()` / `DispatchOnDispatcherShape.toDispatchShape()`
normalized the legacy nullable fields into these states through stages 2–7; they
were deleted in stage 8 once both analyzers emitted `DispatchShape` directly. The
mapping the analyzers now apply inline: simple `@PacketType` → `FixedByte` +
Monomorphic + Unframed + Disabled; `@DispatchOn` → `ValueClass` with feature
fields mapped one-for-one.

## Staged migration (each stage byte-identical; gate = `codec-snapshots/` unchanged)

0. **Characterization** — confirm the snapshot suite covers FixedByte goldens
   (`CommandCodec`, `ConnectionStatusCodec`) and ValueClass goldens (`Tcp`,
   `Ethernet`, `Mqtt`, `Slice14cGeneric`, `ForwardCompatibleOp`). No code change.
1. **Add the IR types** (above). Pure additions, nothing wired.
2. **Adapters** — `DispatcherShape.toDispatchShape()` /
   `DispatchOnDispatcherShape.toDispatchShape()`. Unused → byte-identical.
3. **Migrate decode** through one `buildDispatchDecodeFun(DispatchShape)` forking
   on ownership/labelFormat. Highest-confidence first (ownership most visible).
4. **Migrate encode** (preserve the ConsumedByDispatcher `writeUByte` + generic-cast machinery).
5. **Migrate wireSize** — riskiest (the `1 +` aggregation fork). Pin
   `CommandCodec.kt:44-47` and `ConnectionStatusCodec.kt:54-59`.
6. **Migrate peek** — the `+1`/`1 +` fork + `@FramedBy` single-walker routing.
7. **Migrate the file/TypeSpec shell** — one builder choosing object / class<P> /
   framed / aggregator. Old two builders become wrappers, then deleted.
8. **Collapse the analyzers** — both produce `DispatchShape` directly.
9. **Fold dispatcher analysis into a result sum type** — both analyzers return
   `DispatchAnalysisResult` (the dispatcher mirror of `AnalysisResult`, since
   `AnalysisResult.Supported` wraps a `CodecShape`, not a `DispatchShape`). The
   `?: return null` silent-skips split into `NotApplicable` (validator-paired /
   fall-through / variant-self-reported) and `Rejected` (the two true silent
   gaps: simple-path non-data/object variant; non-peekable `@DispatchOn`
   discriminator inner). Existing goldens unchanged (rejected shapes emitted
   nothing before); negative tests added to `EmitterDiagnosticsValidatorTest`.
   The broken-codegen `unify-dispatch` rows (#4/#6 generic-parent simple
   dispatch, #22/#23 peek/wireSize asymmetry) were addressed structurally by
   stages 3–8 (one builder set), not by this diagnostics pass.

Order rationale: zero-risk IR/adapters first, then one byte-emit concern per
stage from most-isolated (decode) to most-error-prone (wireSize), so any golden
diff localizes the regression to that stage.

## How varint slots in afterward (gateway confirmed)

Adding HTTP/3 varint is then additive: a `Discriminator.Varint`
(`WireWidth.Variable`, `ReReadByVariant`) plus two small functions — a QUIC
varint **read** (1/2/4/8 bytes from the 2 high bits of byte 0) and **peek**
(read the length-class bits, then check availability). The one place that needs
new logic is the peek min-bytes guard: `WireWidth.Variable` can't pre-compute a
fixed width, so the `requireFixed("dispatcher")` peek call becomes a
runtime-measured branch (this is the documented Phase-2 attach point for the
`WireWidth.Variable` stub). encode/wireSize/file-shell need no changes because
Varint is `ReReadByVariant` (the variant counts it, dispatcher pure-delegates).

## Post-unification: safe-to-add follow-ups (roadmap)

Now that both dispatch paths share one `DispatchShape` + one emit builder set,
several `SUPPORT_MATRIX.md` backlog rows became *safe* to add — meaning
byte-identical for existing goldens (snapshot-gated), leveraging infra that
already exists on a parallel path, with a round-trip test as the correctness
gate. `SUPPORT_MATRIX.md` stays the canonical gap inventory; this is the
prioritization lens (row numbers reference it).

The template the unification establishes: a feature already working on one path
becomes a small, byte-identical change on the other because
`branchTypeName(Monomorphic) == className` and `codecReceiver(StaticObject)`
equals the old static ref — so generic/feature code emits *only* when the new
shape is present. (Proven by the generics-under-simple-`@PacketType` win,
matrix #3/#4.)

**Tier 1 — safe capability wins (infra exists, one path missing a piece):**

- **#1/#2 — multi-byte `@DispatchOn` discriminators** (signed `Short`/`Int`/`Long`,
  unsigned `ULong`). **DONE** (branch `codec/multibyte-dispatchon-discriminator`).
  Decode/encode already worked via the value-class-field path; the only gap was
  the dispatcher's `peekFrameSize` byte-reconstruction. Flipped the clean seam:
  `peekableDispatcherInnerKinds` now covers every integer kind, and
  `appendPeekFixedScalar` reconstructs Int-domain (2/4-byte: `Int and 0xFF`
  shifts) and Long-domain (8-byte: `Long and 0xFFL` shifts) inners, order-aware
  per the discriminator's `wireOrder`, narrowing sign-preservingly to the inner
  kind. The stage-9 "non-peekable" diagnostic is now a defensive guard for the
  non-integer (Float/Double) inners the validator already rejects. New fixture
  `multibytedisc/MultiByteDispatchFrames` (one frame per inner kind, mixing
  big/little-endian) + cross-platform round-trip/peek test; stage-9 negative test
  #17 flipped Rejected→Supported. Byte-identical (305 existing goldens unchanged;
  16 new goldens added). **This was the on-ramp to varint/H3** (variable width is
  the same machinery one step on — the Long-domain peek assembly is the shape a
  QUIC 8-byte varint reuses).
- **#7/#26 — `@LengthFrom` value-class-wrapped scalar sibling / non-peekable
  inner.** The validator already accepts these; the emitter rejects (drift gap).
  Fix = widen one accepted-kinds set.
- **#28 — bare nested `@RemainingBytes val: SomeMessage`** (not `List`). Mirrors
  the existing `List<@ProtocolMessage>` path.
- **#22/#23 — data-object variant under simple `@PacketType` + peek/wireSize
  asymmetry.** Almost certainly already closed structurally by the one-builder
  set; near-zero-risk "add a fixture + round-trip test to prove and lock it".

**Tier 2 — loudness, not capability (cheap DX):** convert remaining silent gaps
to diagnostics (#8–#12, #16, #36–#38). Trivially safe (fire only on
already-broken shapes). NB: the `AnalysisResult` diagnostics pass + stage 9
already closed many — audit `EmitterDiagnosticsValidatorTest` first to find the
genuine stragglers.

**Tier 3 — NOT safe yet (design work / fragile paths):**

- **#6 — generic + `@FramedBy` on the simple path.** Framed-encode infra is
  still `@DispatchOn`-only; re-introduces path-specific feature code.
- **#14/#15 — signed scalar with explicit `@WireOrder` (#154).** Touches the
  fragile manual byte-assembly path (sign handling); flagged risky in-code.
- **#24 — `@ForwardCompatible` + generic framed round-trip.** Unchecked
  star-projected casts + `payloadCodec` injection into the Unknown re-frame;
  compiles but runtime-fragile.
- **Supporting (vs rejecting) non-terminal length fields** — needs wireSize
  composition that doesn't exist; a design project, not an add.

Recommended next: **#1/#2** (highest leverage — also the varint on-ramp), then
**#22/#23** (cheapest — likely just prove-and-lock).

## Top byte-identity risks

1. **Discriminator-counted-once** (`1 +` only under ConsumedByDispatcher) — the
   easiest ±1 regression across every dispatcher.
2. **Hex vs decimal label radix** — threaded to `when` labels, expected-set, and
   (FixedByte only, always hex) the encode `writeUByte` literal.
3. **Peek/rewind line presence** — ValueClass emits `buffer.position(...)`,
   FixedByte must not.
4. **Variable-name divergence** (`discriminator` vs `__discriminator`/`__dispatchValue`).
5. **Generic-cast coupling** — `@Suppress("UNCHECKED_CAST")` + `value as X<P>` +
   star-projected `is X<*>` only for generic variants.
6. **`@FramedBy` triple-coupling** — drops `OVERRIDE`, drops `Codec<T>`
   superinterface, omits wireSize.
7. **Large-decimal underscoring** — keep `%L` on the `Int` for Decimal labels;
   keep the pre-formatted hex string for Hex labels. Don't unify into one path.
