# Dispatch-path unification plan

Collapse the two sealed-dispatch codegen paths into a single dispatch IR
parameterized by a `Discriminator` sum type, executed as a byte-identical,
snapshot-gated staged refactor. This is the gateway to an HTTP/3 QUIC-varint
discriminator.

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

## Unified IR (in CodecIr.kt)

```kotlin
internal data class DispatchShape(
    val packageName: String,
    val parentClassName: ClassName,
    val parentSimpleName: String,
    val codecSimpleName: String,
    val discriminator: Discriminator,
    val variants: List<DispatchVariant>,
    val payloadTypeParameter: PayloadTypeParameter? = null, // orthogonal
    val framedBy: FramedByConfig? = null,                   // orthogonal
    val forwardCompatible: ForwardCompatibleConfig? = null, // orthogonal
    val visibility: KModifier? = null,
)

internal data class DispatchVariant(
    val simpleName: String,
    val className: ClassName,
    val codecClassName: ClassName,
    val dispatchValue: Int,
    val genericInstanceFieldName: String? = null,
    // Consulted ONLY when ownership == ConsumedByDispatcher (dispatcher aggregates
    // the discriminator into the variant size). Ignored for ReReadByVariant.
    val wireSize: VariantWireSize,
)

internal sealed interface Discriminator {
    val labelFormat: LabelFormat
    val wireWidth: WireWidth
    val ownership: DiscriminatorOwnership

    data class FixedByte(                       // simple @PacketType
        override val labelFormat: LabelFormat = LabelFormat.Hex,
        override val ownership: DiscriminatorOwnership = DiscriminatorOwnership.ConsumedByDispatcher,
    ) : Discriminator { override val wireWidth get() = WireWidth.Fixed(1) }

    data class ValueClass(                      // @DispatchOn(value class)
        val className: ClassName,
        val codecClassName: ClassName,
        val innerKind: ScalarKind,
        val innerWireOrder: Endianness,
        val dispatchValueProperty: String,
        val dispatchValueKind: ScalarKind = ScalarKind.Int,
        override val labelFormat: LabelFormat = LabelFormat.Decimal,
        override val ownership: DiscriminatorOwnership = DiscriminatorOwnership.ReReadByVariant,
    ) : Discriminator { override val wireWidth get() = innerKind.wireWidth }

    data class Varint(                          // reserved — HTTP/3
        val dispatchValueProperty: String,
        val dispatchValueKind: ScalarKind = ScalarKind.Int,
        override val labelFormat: LabelFormat = LabelFormat.Decimal,
        override val ownership: DiscriminatorOwnership = DiscriminatorOwnership.ReReadByVariant,
    ) : Discriminator { override val wireWidth get() = WireWidth.Variable }
}

internal enum class DiscriminatorOwnership { ConsumedByDispatcher, ReReadByVariant }
internal enum class LabelFormat { Hex, Decimal }
```

`DispatcherShape` → `FixedByte`; `DispatchOnDispatcherShape` → `ValueClass`
(feature fields copy across one-for-one).

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
9. **Fold dispatcher analysis into `AnalysisResult`** — the `?: return null`
   dispatcher silent-skips become `Rejected` diagnostics (closes unify-dispatch
   backlog #3–6, #22–25). Existing goldens unchanged (rejected shapes emitted
   nothing before); add negative tests for the previously-silent gaps.

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
