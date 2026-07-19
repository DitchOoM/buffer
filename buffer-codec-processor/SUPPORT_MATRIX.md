# buffer-codec KSP Processor — Support Matrix

> Canonical, axis-by-axis statement of **what the codec processor supports** and **what it rejects with a diagnostic.**
> The historical purpose of this document was to make a third category — the *silent gap* — explicit. The dispatch-path
> unification (PRs #177–#180) collapsed the two sealed-dispatch analyzers into one `DispatchShape` IR and closed almost
> all of those gaps structurally; this document has been re-verified against the current source and reflects that reality.

## 1. Purpose & the three-outcome model

Every annotated shape a user can write lands in one of three outcomes when it passes through the processor
(`ProtocolMessageProcessor` validator → `CodecAnalyzer` analysis → `CodecEmitter*` emit):

| Outcome | Meaning | User experience | Good or bad? |
|---------|---------|-----------------|--------------|
| **1. Supported** | Analyzer returns `Supported`; the emitter generates a codec. | Codec compiles and round-trips. | Good. |
| **2. Rejected-with-diagnostic** | The validator, or the analyzer's own `Rejected` result, detects an out-of-scope shape and emits a KSP error anchored on the offending element. | Build fails with a precise, actionable message. | Good — the *correct* way to be unsupported. |
| **3. Silent gap** | A shape produces no codec (or a broken one) with **no diagnostic**. | Code compiles, but the failure surfaces at runtime or as a missing symbol. | **Bad — the historical bug class.** |

**Why Outcome 3 is now rare.** The processor no longer has two independently-drifting notions of "supported." Analysis
is modeled as explicit sum types in `CodecIr.kt`:

- `AnalysisResult` (`Supported` / `Rejected` / `NotApplicable`) for a top-level `@ProtocolMessage`,
- `DispatchAnalysisResult` (same three) for a sealed dispatcher parent,
- `FieldAnalysis` (`Ok` / `Err`) for a single constructor parameter.

`CodecEmitter.tryEmit` turns every `Rejected`/`Err` into a `logger.error(...)` KSP error (`CodecEmitter.kt` `tryEmit`,
the `is …Rejected -> … logger.error` arms). So a shape the analyzer *recognizes but cannot handle* now fails **loudly**.
`NotApplicable` is returned only when the symbol is not this analyzer's concern, or is already rejected by a validator
diagnostic — staying silent there avoids double-reporting. A residual silent gap can therefore only exist where a
`NotApplicable` was returned on the assumption the validator catches the shape, but the validator does not. The confirmed
residuals are listed in §3.

**One dispatch path.** There is no longer a "simple `@PacketType`" analyzer and a separate "`@DispatchOn`" analyzer with
drifting feature sets. Both `analyzeSealedDispatcher` and `analyzeDispatchOnSealedDispatcher` (`CodecAnalyzer.kt`) produce
a single unified `DispatchShape` IR, and one set of emit builders (`buildDispatchDecodeFun` / `buildDispatchEncodeFun` /
`buildDispatchWireSizeFun` / `buildDispatchPeekFun` / `buildDispatchFileSpec` in `CodecEmitterDispatch.kt`) consume it.
The dimensions that used to be path-gated — discriminator kind, payload genericity, framing, forward-compat — are now
**orthogonal fields of `DispatchShape`**. See §4.

---

## 2. Per-axis support tables

- [2.1 Dispatch & discriminators](#21-dispatch--discriminators)
- [2.2 Payload genericity](#22-payload-genericity)
- [2.3 Length & field shapes](#23-length--field-shapes)
- [2.4 Wire-order, nesting & value classes](#24-wire-order-nesting--value-classes)

Evidence columns cite either a **fixture** under `buffer-codec-test/` (source in `src/…`, generated snapshot in
`codec-snapshots/…`) or a **current symbol** (`File.kt symbolName`). Line numbers are deliberately omitted — they drift.

---

### 2.1 Dispatch & discriminators

#### Supported

| Shape | Evidence |
|-------|----------|
| Simple `@PacketType` single-byte (`Discriminator.FixedByte`) dispatcher | `simple/Command` (Ping/Echo), `tcp/TcpSegmentByFlags` |
| `@DispatchOn` fixed-width discriminator (`Discriminator.ValueClass`) with `@DispatchValue`, **all 8 integer inner kinds** (UByte, Byte, UShort, Short, UInt, Int, ULong, Long) | `ethernet/EthernetFrameByEtherType` (UShort), `mqtt/suback/MqttV3SubAckReturnCode` (UByte); `CodecAnalyzer.kt peekableDispatcherInnerKinds` |
| `@DispatchOn` self-delimiting **varint** discriminator (`Discriminator.Varint`, QUIC/HTTP-3 style) | `http3/Http3Frame`, `usecodecscalar/DispatchVarintUnion` |
| Data class **and** data-object/object variants under either discriminator | `mqtt/suback/MqttV3SubAckReturnCode`, `tls/TlsHandshake` (HelloRequest) |
| `@DispatchValue` return kinds Boolean/Byte/UByte/Short/UShort/Int/UInt with per-kind `@PacketType.value` range validation | `ethernet/EthernetFrameByEtherType`; validator `validateDispatchOnSealed` |
| Generic payload `<P : Payload>` dispatcher with constructor-injected variant codec, under **both** `@DispatchOn` and simple `@PacketType` | `slice14cgeneric/Slice14cGenericFramedDispatch` (`@DispatchOn`), `simplegeneric/SimpleGenericFrame` (simple `@PacketType`) |
| `@FramedBy` on a dispatcher (framing codec, `after` field, slicing-scheme encode returning `ReadBuffer`) | `slice14c/Slice14cFramedDispatch`, `forwardcompat/ForwardCompatibleOp`, `http3fc/Http3FcFrame` (framed over varint) |
| `@ForwardCompatible` unknown-variant sink (`@UnknownVariant`, skip+preserve decode, re-frame encode), over single-byte **and** varint discriminators | `forwardcompat/ForwardCompatibleOp`, `http3fc/Http3FcFrame` |
| Per-variant `wireSize` classification (LiteralExact / RuntimeExact / BackPatch / Delegated) with discriminator-byte aggregation | `simple/Command` (`Exact(9)` Ping, BackPatch Echo); `VariantWireSize` in `CodecIr.kt` |
| Multi-byte discriminator `peekFrameSize` with wire-order-aware byte assembly | `ethernet/EthernetFrameByEtherType`; `buildDispatchPeekFun` |
| Consumer-supplied `FrameDetector` companion overriding dispatcher `peekFrameSize` (`DispatchShape.customPeek`) | WebSocket fixtures; `detectCustomFramePeek` |

#### Rejected (with diagnostic)

All validated in `ProtocolMessageProcessor.kt`; the analyzer's own `DispatchAnalysisResult.Rejected` covers the few
cases with no paired validator diagnostic. Diagnostic text below is quoted or paraphrased from the current source.

| Shape | Diagnostic (source) |
|-------|---------------------|
| Simple `@PacketType` variant missing `@PacketType` | "Every variant of a simple sealed dispatch parent must carry @PacketType(value = N) where N in 0..255" — `validateSealedDispatcher` |
| Simple `@PacketType` value out of `0..255` / duplicate | "@PacketType(N) … is out of range" / "duplicates the value already declared" — `validateSealedDispatcher` |
| Non-data, non-object variant under a simple `@PacketType` parent | analyzer `Rejected`: "must be a `data class` or `data object` / `object`. Non-data class variants are not supported." — `analyzeSealedDispatcher` |
| `@DispatchOn` discriminator not a `@JvmInline value class` | "The discriminator must be a `@JvmInline value class` …" — `validateDispatchOnSealed` |
| `@DispatchOn` discriminator inner not a non-nullable numeric scalar | `validateDispatchOnSealed` |
| Zero or multiple `@DispatchValue` properties | "declare exactly one property annotated with `@DispatchValue`" — `validateDispatchOnSealed` |
| `@DispatchValue` mutable / wrong return type | `validateDispatchOnSealed` |
| `@DispatchOn` variant missing `@PacketType` / value out of the discriminator's range / duplicate | "@DispatchOn variant … is missing `@PacketType(value = N)`" / "is out of range for the parent's `@DispatchValue` return type" / "duplicates the value already declared" — `validateDispatchOnSealed` |
| `@DispatchOn` discriminator inner is Float/Double (non-integer) | analyzer `Rejected`: "not a supported dispatch discriminator type … Float / Double cannot be dispatch discriminators" — `analyzeDispatchOnSealedDispatcher` (belt-and-suspenders; the validator's numeric-inner check reports first) |
| Generic-payload variant under a **non-generic** sealed parent | "has generic-payload variant(s) … but the parent itself is not generic. This shape is type-unsafe …" — `validateGenericPayloadVariantShape` |
| `@ForwardCompatible` without `@FramedBy` | `validateForwardCompatible` |

---

### 2.2 Payload genericity

Genericity is one field of the unified IR (`Genericity.Monomorphic` / `Genericity.Generic(binding)` in `CodecIr.kt`),
applied identically to standalone codecs (`CodecShape.payloadTypeParameter`) and dispatchers (`DispatchShape.genericity`).

#### Supported

| Shape | Evidence |
|-------|----------|
| Standalone data class `<P : Payload>` with `@RemainingBytes val: P` | `slice10e/Slice10ePayload`; `detectPayloadTypeParameter` |
| Generic sealed parent `<out P : Payload>` under `@DispatchOn`, mixed generic + non-generic variants | `slice14cgeneric/Slice14cGenericFramedDispatch` |
| Generic sealed parent `<out P : Payload>` under **simple** `@PacketType` | `simplegeneric/SimpleGenericFrame` |
| Generic parent emitted as `class FooCodec<P : Payload>(payloadCodec)` (not `object`) for any `Genericity.Generic`, including `FixedByte` | `buildDispatchFileSpec` (`Genericity.Generic` arm) |
| Generic variant carrying `<P : Payload>` referenced from the dispatcher as a constructor-injected `GenericInstance` codec field | `VariantCodecRef.GenericInstance`; `analyzeSealedDispatcher` / `analyzeDispatchOnSealedDispatcher` |
| `Partial<P>` aggregator machinery for constructor-injected codec resolution | snapshot `slice10e/RemoteCommandCodec.kt` (`Partial<P>`); `buildDispatchOnAggregatorCompanion` |

#### Rejected (with diagnostic)

| Shape | Diagnostic (source) |
|-------|---------------------|
| Type parameter with a non-`Payload` bound | `validatePayloadTypeParameter` |
| More than one type parameter | `validatePayloadTypeParameter` |
| Generic variant under a non-generic parent | `validateGenericPayloadVariantShape` (see §2.1) |
| Generic-typed field routed through by-name `@ProtocolMessage` framing (its codec is a class, not a singleton object) | `validateUseCodec` — "generic `<P : Payload>` constructor-injected codec resolution … use a concrete `Payload` subtype" |

---

### 2.3 Length & field shapes

Field classification lives in `CodecAnalyzer.kt` (`analyze*Field` functions) and is modeled by `FieldSpec` in
`CodecIr.kt`. The following field shapes are supported (see the `FieldSpec` members and their kdoc for the exact wire
contract):

#### Supported

| Shape | `FieldSpec` / evidence |
|-------|------------------------|
| `@LengthPrefixed val: String` (or value class over `String`) | `LengthPrefixedString`; `simple/TwoStrings` |
| `@LengthPrefixed val: @ProtocolMessage` (terminal) | `LengthPrefixedMessage` |
| `@LengthPrefixed @UseCodec(BoundingLengthCodec<UInt>) val: List<@ProtocolMessage>` | `LengthPrefixedUseCodecList`; MQTT v5 property bag |
| `@LengthPrefixed @UseCodec(Codec<T>) val: T : Payload` | `LengthPrefixedUseCodecPayload`; MQTT CONNECT will/password |
| `@LengthFrom(sibling) val: String` / `List<@ProtocolMessage>` / `@ProtocolMessage` | `LengthFromString` / `LengthFromList` / `LengthFromMessage` |
| `@LengthFrom("sibling.property")` dotted value-class-property form | `LengthSource.ValueClassProperty`; HTTP/2 SETTINGS |
| `@RemainingBytes val: String` (or value class over `String`) | `RemainingBytesString` |
| `@RemainingBytes val: @ProtocolMessage` bare, or `List<@ProtocolMessage>` | `RemainingBytesProtocolMessageList` |
| `@RemainingBytes @UseCodec(Codec) val: P : Payload` | `RemainingBytesPayload`; `BinaryData` |
| `@RemainingBytes val: P` (generic `<P : Payload>`) | `RemainingBytesPayload` with `PayloadCodecSource.ConstructorInjected` |
| `@Count val: List<@ProtocolMessage>` (varint element count, non-terminal) | `CountPrefixedProtocolMessageList` |
| `@When(sibling: Boolean) val: T?` — scalar / value-class scalar / string / `@ProtocolMessage` / `@UseCodec` inner | `Conditional` + `ConditionalInner.*` |
| `@When("sibling.property → Boolean)` dotted form | `ConditionRef.ValueClassProperty`; MQTT `MqttFixedHeader.qosGreaterThanZero` |
| `@When("remaining <op> INT")` grammar-2 | `ConditionRef.RemainingCmp` |
| `@UseCodec(Codec<T>) val: T` (bare scalar, natural width) | `UseCodecScalar`; MQTT remaining-length codec |
| `@UseCodec(VariableLengthCodec<T>) val: T` → message wireSize is runtime-Exact | `UseCodecScalar.isVariableLength`; `varintfield/VarintLengthFrame`, `http3/Http3FrameType` |
| `@WireBytes(N)` on scalar fields (1–8 bytes, N ≤ natural width) | `Scalar` with narrowed `wireBytes` |
| `@JvmInline value class` scalar field (natural-width inner) | `ValueClassScalar` |
| enum field (ordinal as unsigned LEB128 varint; optional `@EnumDefault` unknown-ordinal sink) | `EnumScalar`; `analyzeEnumField`; `enums/Style` |

Enum fields are evolution-safe: the ordinal rides as a self-delimiting varint, so a newer ordinal never breaks an older
decoder's framing. wireSize is runtime-Exact.

#### Rejected (with diagnostic)

Diagnostics live in `ProtocolMessageProcessor.kt` (`validate*`) and `CodecAnalyzer.kt` (`analyze*` `FieldAnalysis.Err`).

| Shape | Diagnostic (source) |
|-------|---------------------|
| Non-terminal `@RemainingBytes` + variable-width trailer | "non-terminal @RemainingBytes …" — `validateRemainingBytesTrailers` |
| `@RemainingBytes List<scalar>` | "Scalar-element list shapes are retired" — `validateRemainingBytesElementType` |
| `@RemainingBytes` primitive array | "Primitive array element types … : Payload with a hand-written Codec<T>" — `validateRemainingBytesElementType` |
| `@LengthFrom` bad bound type / sibling declared at-or-after / non-numeric sibling / dotted-property not value class / property not Int | `validateLengthFrom` (message family begins "@LengthFrom(…") |
| `@When` on a non-nullable type / non-Boolean source / source declared at-or-after / dotted-property not value class or not Boolean / malformed grammar-2 | `validateWhen` |
| `@UseCodec` composed with `@LengthFrom` | "@UseCodec … composed with `@LengthFrom` is not yet supported … deferred to a future release" — `validateUseCodec` |
| `@UseCodec` target not a Kotlin `object` / not implementing `Codec<T>` | `validateUseCodec` |
| `Payload` field without `@UseCodec` / `@RemainingBytes @UseCodec` on a non-`Payload` type | "Payload field requires @UseCodec" — `validateUseCodec` |
| `@LengthPrefixed @UseCodec` codec not `BoundingLengthCodec<UInt>` / List element not `@ProtocolMessage` / element carrying `<P : Payload>` | `validateLengthPrefixedUseCodec` |
| `@WireBytes(N)` out of `1..8` / exceeds natural width | "width must be 1..8" — `validateWireBytes` |
| `@WireBytes` / `@WireOrder` on an enum field | "@WireBytes is not supported on an enum field (its ordinal rides as a varint)" / "@WireOrder is not supported on an enum field" — `analyzeEnumField` |
| enum with more than one `@EnumDefault` entry | "declares N @EnumDefault entries; at most one is allowed" — `analyzeEnumField` |

---

### 2.4 Wire-order, nesting & value classes

#### Supported

| Shape | Evidence |
|-------|----------|
| `@ProtocolMessage(wireOrder)` with unsigned scalars, Float/Double, and natural-width signed scalars | `wireorderMismatch/BigWirePacket` |
| Field-level `@WireOrder` override at natural width | batch fixtures (`MixedOrderFlush` UShort `@WireOrder(Little)`) |
| `@JvmInline value class` over a single supported scalar | `simple/SmallFlags`, `simple/WithFlagPayload` |
| value class as `@DispatchOn` discriminator with `@DispatchValue` | `forwardcompat/OpCode`, `ethernet/EtherType` |
| Nested `@ProtocolMessage` data class / sealed parent field via `@LengthFrom` | `tls/TlsHandshake`, `tls/TlsHandshakeWithSealedBody` |
| Bare nested sealed-interface field with `@PacketType` variants | `CommandPayloadProtocol`, `DeviceState` |
| Batch-encode coalescing of adjacent same-order scalars, respecting per-field `@WireOrder` and value-class `@ProtocolMessage(wireOrder)` | `wireorderMismatch/BigWirePacket`, `MixedOrderFlush`, `MixedOrderValueClass`; `BatchCoalescingCodegenTest` |
| Value-class field + dotted `@LengthFrom("header.length")` | `simple/LeHeader` / `LePacket` (LeHeader wireOrder=Little) |

#### Rejected (with diagnostic)

| Shape | Diagnostic (source) |
|-------|---------------------|
| `@DispatchValue` returning Long/ULong or nullable | `validateDispatchOnSealed` (value space is `Int`) |
| `@DispatchOn` variant's first ctor param not the discriminator type | `validateDispatchOnSealed` |
| `@DispatchValue` declared more than once | `validateDispatchOnSealed` |

---

## 3. Known limitations & residual gaps

The large silent-gap backlog that motivated the original document has been closed by the dispatch unification and the
`analyze → Rejected → logger.error` pass (§1, §4). The genericity-through-simple-dispatch cluster, the multi-byte
signed/unsigned discriminator cluster, the non-data-variant-under-simple-dispatch case, and the varint / `@FramedBy` /
`@ForwardCompatible`-over-varint work all shipped and are covered by fixtures. The following are the limitations that are
still confirmable against the current source:

| Limitation | Nature | Evidence |
|------------|--------|----------|
| `@LengthFrom` length-carrier siblings are limited to `{UByte, Byte, UShort, UInt}` (both the bare-sibling form and the dotted value-class-inner form); `Int` / `Long` / `ULong` carriers are not resolved by the analyzer. | analyzer returns null → no `LengthSource` | `CodecAnalyzer.kt peekableLengthFromSiblingKinds`, `analyzeLengthSource` |
| `peekFrameSize` collapses to `NoFraming` when a message has more than one self-delimiting/variable-length field (multiple enums, or an enum plus a variable-length `@UseCodec` field). A single such field with all-fixed priors and suffix frames correctly. | documented limitation (decode/encode still round-trip; only stream pre-framing is unavailable) | `CodecEmitterPeek.kt` (`isVariableLength` / enum peek → `NoFraming`); `EnumScalar` kdoc in `CodecIr.kt` |

> The `Diagnostic` kdoc at the top of `CodecIr.kt` still claims diagnostics are "constructed but NOT emitted." That
> comment is itself stale: `CodecEmitter.tryEmit` does emit them (`logger.error`). Treat the `tryEmit` code as
> authoritative.

If you discover a shape that produces no codec with no diagnostic, it is a residual `NotApplicable`-assumed-validated
gap: the fix is to convert the analyzer's `NotApplicable` at that site into a `Rejected` (loud), or to add the missing
validator check — never to leave it silent.

---

## 4. The dispatch unification (what shipped)

The processor previously carried two parallel sealed-dispatch analyzers whose feature support drifted, plus a validator
that independently re-derived "supported." That structure is gone. The current design, verifiable in `CodecIr.kt`:

- **One dispatcher IR.** `DispatchShape` subsumes both dispatch paths. `Discriminator` is a sum type —
  `FixedByte` (simple `@PacketType`, dispatcher-consumed), `ValueClass` (`@DispatchOn` fixed-width, peek/rewind +
  re-read-by-variant), and `Varint` (self-delimiting QUIC/HTTP-3 discriminator). `DiscriminatorOwnership`,
  `LabelFormat`, and `WireWidth` are properties of the discriminator, not of a code path.
- **Orthogonal feature dimensions.** `genericity` (`Genericity`), `framing` (`Framing`), and `forwardCompat`
  (`ForwardCompat`) are independent fields of `DispatchShape`. Any combination — e.g. a generic, framed,
  forward-compatible varint dispatcher — is expressible without a bespoke path.
- **One analyze → sum-type pass.** `analyze` / the dispatcher analyzers / field analysis return
  `AnalysisResult` / `DispatchAnalysisResult` / `FieldAnalysis`, never a bare nullable. `tryEmit` maps `Rejected`/`Err`
  to KSP errors and `Supported`/`Ok` to emitted code. A recognized-but-unsupported shape is, by construction, a loud
  diagnostic rather than a silent drop.

The end state the original document argued *toward* is the state the code is now *in*: a single analyze pass over a
unified dispatcher IR, where the discriminator kind, genericity, framing, and forward-compat are free dimensions, and the
only way to be unsupported is to be loud.
