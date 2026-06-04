# buffer-codec KSP Processor — Support Matrix

> Canonical, axis-by-axis statement of **what the codec processor supports, what it rejects with a diagnostic, and what it silently breaks.**
> The point of this document is to make the third category — the silent gap — explicit, so contributors and users stop discovering it through bug reports.

## 1. Purpose & the three-outcome model

Every annotated shape a user can write lands in exactly one of three outcomes when it passes through the processor (`ProtocolMessageProcessor` validator → `CodecEmitter` analyzer/emitter):

| Outcome | Meaning | User experience | Good or bad? |
|---------|---------|-----------------|--------------|
| **1. Supported** | Validator accepts; emitter analyzes and generates a codec. | Codec compiles and round-trips. | Good. |
| **2. Rejected-with-diagnostic** | Validator (or emitter) detects the shape is out of scope and emits a KSP error pointing at the offending element. | Build fails with a precise, actionable message. | Good — this is the *correct* way to be unsupported. |
| **3. OUTCOME 3 — Silent gap** | Validator lets the shape through, but the emitter `return null`s the analysis (or emits structurally-wrong code) with **no diagnostic**. | User code compiles, but **no codec is generated** (or a broken one is), and the failure surfaces only at runtime or as a missing symbol. | **Bad — this is the bug class.** |

**The thesis of this document:** Outcome 3 is a *defect in the processor itself*, not a feature limitation. Every Outcome-3 row below should eventually be converted into either an Outcome-2 (`reject-with-diagnostic`) or an Outcome-1 (`add-support`). The root cause is structural — see [§4 Architecture implications](#4-architecture-implications).

### Why Outcome 3 exists

The validator (`ProtocolMessageProcessor`) and the emitter (`CodecEmitter`) **independently re-analyze** the same annotated declarations. They are two parallel passes with overlapping but non-identical notions of "supported." Wherever the emitter's `return null` rejection set is *larger* than the validator's diagnostic set, the difference falls through as a silent gap. Additionally, there are **two parallel dispatch paths** (`analyzeSealedDispatcher` for simple `@PacketType`, `analyzeDispatchOnSealedDispatcher` for `@DispatchOn`) whose feature support has drifted — a feature added to one is silently missing from the other.

---

## 2. Per-axis support tables

Six axes are inventoried. Each has three tables: **Supported**, **Rejected (with diagnostic)**, and **Silent Gaps (Outcome 3)**.

- [2.1 Dispatch & discriminators](#21-dispatch--discriminators)
- [2.2 Payload genericity](#22-payload-genericity)
- [2.3 Length & field shapes](#23-length--field-shapes)
- [2.4 Wire-order, nesting & value classes](#24-wire-order-nesting--value-classes)
- [2.5 `return null` census (emitter analyze paths)](#25-return-null-census-emitter-analyze-paths)
- [2.6 Validator coverage census](#26-validator-coverage-census)

---

### 2.1 Dispatch & discriminators

#### Supported

| Shape | Evidence |
|-------|----------|
| Simple `@PacketType` single-byte (UByte) discriminator dispatcher | `simple/CommandCodec.kt` (Ping/Echo), `tcp/TcpSegmentByFlagsCodec.kt` |
| `@DispatchOn` multi-byte unsigned & signed-byte discriminators (UByte, Byte, UShort, UInt) with `@DispatchValue` | `ethernet/EthernetFrameByEtherTypeCodec.kt` (UShort), `mqtt/suback/MqttV3SubAckReturnCode.kt` (UByte data objects), `CodecEmitter.kt:2016` `peekableDispatcherInnerKinds = {UByte, Byte, UShort, UInt}` |
| Data class & data object variants in both `@PacketType` and `@DispatchOn` | `mqtt/suback/MqttV3SubAckReturnCode.kt`, `tls/TlsHandshake.kt` (HelloRequest), `ProtocolMessageProcessor.kt:150-171` |
| Dispatch-value widening: Boolean/Byte/UByte/Short/UShort/Int/UInt return types w/ per-kind `@PacketType.value` ranges | `ethernet/EthernetFrameByEtherTypeCodec.kt`, `ProtocolMessageProcessor.kt:381-399` (`DISPATCH_VALUE_RETURN_RANGES`) |
| Generic payload `<P: Payload>` in `@DispatchOn` dispatcher with constructor-injected `payloadCodec` | `slice14cgeneric/Slice14cGenericFramedDispatchCodec.kt` |
| `@FramedBy` on `@DispatchOn` (framing codec, after-field, slicing-scheme `encode` returns `ReadBuffer`) | `slice14c/Slice14cFramedDispatchCodec.kt`, `forwardcompat/ForwardCompatibleOpCodec.kt` |
| `@ForwardCompatible` unknown-variant sink (`@UnknownVariant`, skip+preserve decode, re-frame encode) | `forwardcompat/ForwardCompatibleOpCodec.kt:26-38` (skip+preserve), `:50-58` (re-frame) |
| Simple `@PacketType` wireSize classification (Exact / BackPatch / RuntimeExact per variant w/ discriminator-byte aggregation) | `simple/CommandCodec.kt` wireSize (`Exact(9)` Ping, BackPatch Echo) |
| `@DispatchOn` peek-frame-size w/ multi-byte big-endian discriminator assembly & wireOrder awareness | `ethernet/EthernetFrameByEtherTypeCodec.kt` peekFrameSize:52-55 (2-byte B0/B1 BE assembly) |

#### Rejected (with diagnostic)

| Shape | Diagnostic | Location |
|-------|-----------|----------|
| `@PacketType` value out-of-range | `@PacketType(value) is out of range for the return type's value space` | `ProtocolMessageProcessor.kt:244-249, 450-457` |
| Duplicate `@PacketType` values within sealed parent | `@PacketType(N) duplicates value already declared by sibling variant` | `ProtocolMessageProcessor.kt:252-260, 459-466` |
| `@DispatchOn` discriminator not `@JvmInline value class` | `discriminator must be @JvmInline value class with single numeric scalar inner` | `ProtocolMessageProcessor.kt:310-318` |
| `@DispatchOn` discriminator inner not numeric scalar / nullable | `discriminator's inner parameter must be non-nullable numeric scalar` | `ProtocolMessageProcessor.kt:329-347` |
| Multiple or zero `@DispatchValue` properties | `discriminator must declare exactly one @DispatchValue property` | `ProtocolMessageProcessor.kt:362-368` |
| `@DispatchValue` mutable / has extension receiver | `@DispatchValue must be immutable val on the value class itself` | `ProtocolMessageProcessor.kt:371-377` |
| `@DispatchValue` returns non-Int-coercible type | `@DispatchValue must return one of {Boolean, Byte, UByte, Short, UShort, Int, UInt} non-nullable` | `ProtocolMessageProcessor.kt:388-397` |
| `@DispatchOn` variant missing `@PacketType` | `every variant must carry @PacketType(value = N) with N in range` | `ProtocolMessageProcessor.kt:430-437` |
| `@DispatchOn` data-class variant missing discriminator as first ctor param | `variant must declare first constructor parameter as the discriminator type` | `ProtocolMessageProcessor.kt:479-488` |
| `@DispatchOn` variant non-data, non-object class | `variant must be data class or data object` | `ProtocolMessageProcessor.kt:414-420` |
| `@ForwardCompatible` without `@FramedBy` | `@ForwardCompatible only valid on sealed parents carrying @FramedBy` | `ProtocolMessageProcessor.kt:56-57`, analyzer `:6997` |

#### Silent Gaps (Outcome 3)

| Shape | Outcome | Severity | Issue | Evidence |
|-------|---------|----------|-------|----------|
| Signed multi-byte discriminators (Short, Long) in `@DispatchOn` | silent-skip | **high** | #176 | `CodecEmitter.kt:2016` set excludes Short/Long; `:6950` `if (innerKind !in peekableDispatcherInnerKinds) return null`. Comment `:6946-6949`: "signed multi-byte discriminators aren't required by any in-scope vector." |
| Unsigned multi-byte discriminators (ULong) in `@DispatchOn` | silent-skip | **high** | #176 | `CodecEmitter.kt:2016` excludes ULong; `:6950` returns null silently. |
| Generic payload `<P: Payload>` under **simple** `@PacketType` dispatcher | silent-skip | **high** | #176 | `analyzeSealedDispatcher` (`:6862-6914`) never calls `detectPayloadTypeParameter`; `:6885` `analyze(sub) ?: return null` fails because variant codecs need injected `payloadCodec`. |
| Data object variant under simple `@PacketType` with variable-width peekFrameSize | silent-wrong-runtime | medium | — | `:6874` accepts data objects but `peekFrameSize` (`:7373-7410`) uses per-variant dispatch, not a unified walk; no test coverage of the combination. |
| Asymmetric wireSize: simple `@PacketType` vs `@DispatchOn` non-framed | silent-wrong-runtime | medium | — | `:7332-7367` simple aggregates `1 + variant.wireSize`; `:7464` `buildDispatchOnWireSizeFun` delegates **without** aggregating the discriminator. Callers get different semantics. |
| `@DispatchOn` framed generic variant + `@ForwardCompatible` unknown-variant round-trip | silent-broken-codegen | medium | — | `:7516` `appendForwardCompatibleEncodeArm` inside framed encode; generic variant uses unchecked star-projected cast (`:7500-7514`). `ForwardCompatibleFactoryKey` may not inject `payloadCodec` for Unknown re-frame; compiles, fails at runtime. |

> Note: this axis also has correctly-rejected silent paths that are *paired with a validator diagnostic* (e.g. simple `@PacketType` with generic parent, `:6862`; `@ForwardCompatible` wrong unknown-variant ctor shape, `:7122-7129`). Those are listed in the inventory but are **borderline** — the validator catches most but `resolveForwardCompatibleConfig` returning null on shape mismatch is a true silent drop and should be reconfirmed against `ProtocolMessageProcessor.kt:1966` (F5).

---

### 2.2 Payload genericity

#### Supported

| Shape | Evidence |
|-------|----------|
| Standalone data class `<P : Payload>` with `@RemainingBytes val: P` | `slice10e/Slice10ePayload.kt`; snapshot `slice10e/RemoteCommandCodec.kt:69-79` (`Partial<P>`) |
| `@DispatchOn` generic sealed parent `<out P : Payload>` with generic variants | `slice14cgeneric/Slice14cGenericFramedDispatch.kt`; snapshot `Slice14cGenericFramedDispatchCodec.kt:18` (`class …Codec<P : Payload>`) |
| `@DispatchOn` generic parent with constructor-injected payload codec in dispatcher + variant codecs | `CodecEmitter.kt:7440,7444` (`buildDispatchOnDispatcherFileSpec` / `buildGenericDispatchOnDispatcherTypeSpec`), `:8258` (`payloadTypeParameter`), `:6983` `detectPayloadTypeParameter`, `:7054` `genericInstanceFieldName` |
| Generic variant under generic parent with `@FramedBy`, inheriting framing | snapshot `Slice14cGenericFramedDispatchWithPayloadCodec.kt` (`Partial<P>:108`, companion `partial<P>:124`) |
| `@DispatchOn` generic parent with mixed generic + non-generic (Nothing-typed) variants | `slice14cgeneric/Slice14cGenericFramedDispatch.kt` (Headered + WithPayload<P>); snapshot `:19-22,31` |
| Generic aggregator machinery (`Partial<P>` / aggregator companion) for constructor-injected codec resolution | `CodecEmitter.kt:2520-2521` `buildPartialClassTypeSpec`; `buildPartialCompanionObject` reified `partial<P>`; snapshot `RemoteCommandCodec.kt:69-78` |

#### Rejected (with diagnostic)

| Shape | Diagnostic | Location |
|-------|-----------|----------|
| Sealed parent `<P : Payload>` but no variant uses the type parameter | `declares <P : Payload> but no @RemainingBytes val: P field uses it` | `ProtocolMessageProcessor.kt:2052-2062` *(only applies to data classes, not sealed parents, due to control flow at `:136-142`)* |
| Type parameter with non-Payload bound | `type parameter must be bounded by com.ditchoom.buffer.codec.Payload` | `ProtocolMessageProcessor.kt:2025-2033`; `CodecEmitter.detectPayloadTypeParameter:488` returns null |
| Multiple type parameters `<P, Q>` | `at most one (<P : Payload>) is supported` | `ProtocolMessageProcessor.kt:1999-2006` |

#### Silent Gaps (Outcome 3)

| Shape | Outcome | Severity | Issue | Evidence |
|-------|---------|----------|-------|----------|
| Simple sealed `@PacketType` dispatcher with generic parent `<out P : Payload>` | silent-broken-codegen | **high** | #176 | `analyzeSealedDispatcher` (`:6862-6914`) never calls `detectPayloadTypeParameter`; `DispatcherShape.payloadTypeParameter` doesn't exist (`:8208-8216`). `buildSealedDispatcherFileSpec` (`:7246-7261`) always emits `object …Codec`, not `class …Codec<P>`. Erasure masks Nothing-typed decode but breaks any concrete `Payload` instantiation. |
| Generic variant under **non-generic** sealed parent (variant carries `<P : Payload>`) | silent-broken-codegen | **high** | #176 | `analyzeDispatchOnSealedDispatcher:7051-7077` validates parent must also be generic (`:7061`) and skips silently otherwise — but the **simple** path (`analyzeSealedDispatcher`) has no equivalent check; emits a generic variant codec whose injected `payloadCodec` is never supplied. |
| `Partial<P>` aggregator pattern: generic variant under non-generic sealed parent | silent-broken-codegen | medium | #176 | `buildPartialCompanionObject` (~`:2521`) generates `partial<P>`, but a simple `object` dispatcher has no generic infrastructure to reach it; `decodeAggregating` (snapshot `:72-88`) exists only because the parent IS `@DispatchOn`. |
| Generic sealed parent with `@PacketType` + `@FramedBy`, no `@DispatchOn` | silent-broken-codegen | **high** | #176 | `buildSealedDispatcherFileSpec:7246` emits singleton object; can't parameterize encode return type or thread the codec. Framed-encode infra exists only in `buildFramedByDispatchOnEncodeFun:7475-7526`; simple framed path absent. |

---

### 2.3 Length & field shapes

#### Supported

| Shape | Evidence |
|-------|----------|
| `@LengthPrefixed val: String` (terminal & non-terminal) | `CodecEmitter.kt:687-757`; `TwoStrings.kt`, snapshot `TwoStringsCodec.kt` |
| `@LengthPrefixed val: @ProtocolMessage` (terminal-only) | `CodecEmitter.kt:733-757` (`:738` isTerminal); validator `:1048-1074` |
| `@LengthPrefixed @UseCodec(BoundingLengthCodec<UInt>) val: List<@ProtocolMessage>` | `CodecEmitter.kt:698-716,1288-1312,1808-1853`; validator `:1335-1350,1425-1570` |
| `@LengthPrefixed @UseCodec(Codec<T>) val: T` (Payload/String scalars) | `CodecEmitter.kt:1314-1379`; validator `:1463-1511`; MQTT CONNECT will-payload + password |
| `@LengthFrom(sibling) val: String` | `CodecEmitter.kt:894-916` |
| `@LengthFrom(sibling) val: List<@ProtocolMessage>` (terminal-only) | `CodecEmitter.kt:989-1046,286-287` |
| `@LengthFrom(sibling) val: @ProtocolMessage` (terminal-only) | `CodecEmitter.kt:1047-1107,292` |
| `@LengthFrom(sibling.property: ValueClassProperty→Int)` dotted form | `CodecEmitter.kt:928-972`; validator `:1077-1192`; HTTP/2 SETTINGS |
| `@RemainingBytes val: String` | `CodecEmitter.kt:625-630`; validator `:599-620` |
| `@RemainingBytes val: @ProtocolMessage` (nested body) | `CodecEmitter.kt:1175-1214` |
| `@RemainingBytes val: List<@ProtocolMessage>` | `CodecEmitter.kt:1109-1167`; `Slice11aProbe.kt`, `CommandPayloadProtocol.kt` |
| `@RemainingBytes @UseCodec(Codec) val: P : Payload` | `CodecEmitter.kt:612-614,1216-1248`; `BinaryData.kt` |
| `@RemainingBytes val: P` (generic `<P : Payload>`) | `CodecEmitter.kt:615-622`; validator `:1992-2064`; `CommandFrame.kt` |
| `@When(sibling: Boolean) val: T?` (scalar / value-class scalar / string / sealed parent / codec) | `CodecEmitter.kt:1545-1727`; `WithOptional.kt`, WebSocket fixtures |
| `@When(sibling.property→Boolean)` dotted form | `CodecEmitter.kt:1727-1796`; MQTT `MqttFixedHeader.qosGreaterThanZero` |
| `@When("remaining >= INT")` grammar-2 | validator `:753-776`; `CodecEmitter.kt:9116` (RemainingCmp) |
| `@When @LengthPrefixed val: String?` | `CodecEmitter.kt:1764-1773` |
| `@When @LengthPrefixed @UseCodec val: List<@ProtocolMessage>?` | `CodecEmitter.kt:1737-1747`; MQTT v5 property cascade |
| `@When @LengthPrefixed @UseCodec val: P : Payload?` | `CodecEmitter.kt:1748-1753`; validator `:1824-1853` |
| `@When @UseCodec val: T?` (sealed via hand-written codec) | `CodecEmitter.kt:1755-1762`; `Slice11aProbe.kt` |
| `@UseCodec(Codec<T>) val: T` (non-Payload scalar, natural width) | `CodecEmitter.kt:760-770`; MQTT `MqttRemainingLengthCodec` |
| `@WireBytes(N)` on Scalar fields (1-8 bytes) | `CodecEmitter.kt:806-823`; validator `:654-695` |
| value-class scalar field (natural-width inner) | `CodecEmitter.kt:835-881` |
| `@When val: value-class scalar?` | `CodecEmitter.kt:1788-1793`; MQTT PUBLISH `packetId: PacketId?` |
| bare `val: @ProtocolMessage` (by-name codec resolution) | `CodecEmitter.kt:1175-1214` |
| `@When val: @ProtocolMessage?` (by-name, non-payload-generic) | `CodecEmitter.kt:1913-1937` |

#### Rejected (with diagnostic)

| Shape | Diagnostic | Location |
|-------|-----------|----------|
| `@RemainingBytes` non-terminal + variable-width trailer | `non-terminal @RemainingBytes followed by @LengthPrefixed/@LengthFrom/@RemainingBytes/@When/@UseCodec` | `ProtocolMessageProcessor.kt:534-544` |
| `@RemainingBytes List<scalar>` | `Scalar-element list shapes are retired` (→ sealed-parent dispatcher or @UseCodec Payload) | `ProtocolMessageProcessor.kt:630-648` |
| `@RemainingBytes` primitive array | `Primitive array element types are intentionally not supported` (→ value class : Payload + hand-written Codec) | `ProtocolMessageProcessor.kt:607-616` |
| `@LengthFrom` non-String/List/ProtocolMessage type | `@LengthFrom requires bound field to be String, List<@ProtocolMessage>, or @ProtocolMessage` | `ProtocolMessageProcessor.kt:1065-1072` |
| `@LengthFrom` sibling declared at-or-after | `length-carrier sibling must be declared before bound field` | `ProtocolMessageProcessor.kt:1117-1124` |
| `@LengthFrom("sibling")` sibling non-numeric | `sibling must be non-nullable numeric scalar` | `ProtocolMessageProcessor.kt:1135-1142` |
| `@LengthFrom("sibling.property")` sibling not value class | `sibling must be @JvmInline value class` | `ProtocolMessageProcessor.kt:1152-1158` |
| `@LengthFrom("sibling.property")` property not Int | `property must be Int-returning val` | `ProtocolMessageProcessor.kt:1181-1188` |
| `@When` on non-nullable type | `@When requires field type to be nullable (T?)` | `ProtocolMessageProcessor.kt:737-744` |
| `@When("sibling")` not Boolean | `source must be non-nullable Boolean` | `ProtocolMessageProcessor.kt:837-843` |
| `@When("sibling")` declared at-or-after | `source field must be declared before conditional field` | `ProtocolMessageProcessor.kt:820-826` |
| `@When("sibling.property")` sibling not value class | `sibling must be @JvmInline value class` | `ProtocolMessageProcessor.kt:852-859` |
| `@When("sibling.property")` property not Boolean | `property must be Boolean-returning val` | `ProtocolMessageProcessor.kt:877-884` |
| `@When("remaining …")` malformed grammar-2 | grammar diagnostic (`remaining <op> <int>`, op ∈ {>=,>,==}) | `ProtocolMessageProcessor.kt:765-774` |
| `@UseCodec` + `@LengthFrom`/`@LengthPrefixed` | `not yet supported` | `ProtocolMessageProcessor.kt:1325-1332` |
| `@UseCodec` target not Kotlin object | `codec target must be Kotlin object declaration` | `ProtocolMessageProcessor.kt:1382-1389` |
| `@UseCodec(C)` not implementing `Codec<T>` | `codec does not implement Codec<T>` | `ProtocolMessageProcessor.kt:1394-1400` |
| Payload field without `@UseCodec` | `Payload field requires @UseCodec annotation` | `ProtocolMessageProcessor.kt:1292-1299` |
| `@RemainingBytes @UseCodec` on non-Payload | `field must extend Payload` | `ProtocolMessageProcessor.kt:1363-1370` |
| `@WireBytes(N)` out of range | `width must be 1..8 bytes` | `ProtocolMessageProcessor.kt:674-677` |
| `@WireBytes(N)` exceeds natural width | `width exceeds natural width of type` | `ProtocolMessageProcessor.kt:687-692` |
| `@LengthPrefixed @ProtocolMessage @UseCodec` not `BoundingLengthCodec<UInt>` | `codec does not implement BoundingLengthCodec<UInt>` | `ProtocolMessageProcessor.kt:1514-1522` |
| `@LengthPrefixed @UseCodec` List element not `@ProtocolMessage` | `element must be @ProtocolMessage data class or sealed parent` | `ProtocolMessageProcessor.kt:1545-1553` |
| `@LengthPrefixed @UseCodec` List element with `<P : Payload>` | `element cannot carry <P : Payload> type parameter` | `ProtocolMessageProcessor.kt:1559-1568` |

#### Silent Gaps (Outcome 3)

| Shape | Outcome | Severity | Issue | Evidence |
|-------|---------|----------|-------|----------|
| `@LengthFrom` on value-class-wrapped scalar sibling (non-dotted) | silent-skip | **high** | #163 | `CodecEmitter.kt:944-945` `peekableLengthFromSiblingKinds` accepts plain Scalar only; validator only checks `NUMERIC_SCALAR_QNAMES` (`:1132`), so a value-class sibling passes validator but fails emit. |
| `@LengthPrefixed val: @ProtocolMessage` (non-terminal) | silent-skip | **high** | — | `CodecEmitter.kt:738` isTerminal returns null; no validator diagnostic (contradicts doctrine row 52). |
| `@LengthFrom on @ProtocolMessage` (non-terminal) | silent-skip | **high** | — | `CodecEmitter.kt:292` isTerminal returns null; no validator diagnostic. |
| `@LengthFrom on List<@ProtocolMessage>` (non-terminal) | silent-skip | **high** | — | `CodecEmitter.kt:287` isTerminal returns null; no validator diagnostic. |
| `@LengthFrom` value-class property w/ non-peekable inner kind (ULong/Long) | silent-skip | medium | #163 | `CodecEmitter.kt:959` set `{UByte,Byte,UShort,UInt}`; validator only checks numeric (`:1149` includes ULong/Long), so passes validator, fails emit. |
| value-class scalar field whose inner scalar carries `@WireBytes`/`@WireOrder` | silent-skip | low | — | `CodecEmitter.kt:858-862` returns null; validator doesn't check inner annotations. |
| `@When` conditional with nullable predicate source | silent-skip | low | — | `CodecEmitter.kt:1735` handles bound-field nullability only; predicate source nullability unvalidated by emitter (validator covers via `:834` if not skipped). |
| `@When @LengthPrefixed` on inner `@ProtocolMessage` (non-String) | silent-skip | medium | — | `CodecEmitter.kt:1764-1769` returns null when not `kotlin.String`; no validator rejection. |
| `@RemainingBytes @ProtocolMessage` (bare nested, not List) | silent-skip | medium | #151 | `CodecEmitter.kt:632` `typeQname != LIST_QNAME` returns null; `@RemainingBytes` analyzer handles only String / List<@ProtocolMessage>. |
| `@LengthPrefixedUseCodecList` with non-singleton (generic) element codec | silent-broken-codegen | **high** | — | `CodecEmitter.kt:703-708` calls `<E>Codec.decode` directly; if element carries `<P>` it's a generic class, not singleton. Validator (`:1558-1569`) rejects *resolved* case but pre-slice gap exists. |
| `@When @UseCodec` on sealed parent without `@DispatchOn` | silent-skip | **high** | — | `CodecEmitter.kt:1871-1890` accepts any sealed parent via `classNameOf`; validator accepts (`:1392`); emits non-functional singleton-style call against a non-singleton dispatcher. |
| Multiple bounding fields (`@UseCodec BoundingLengthCodec` + `@LengthPrefixedUseCodecList`) | silent-skip | **high** | — | `CodecEmitter.kt:284` `isBoundingShape() count > 1` returns null; validator never checks for multiple bounding-codec fields. |
| `@When` field with remaining-bytes predicate but non-scalar inner | silent-skip | medium | — | `CodecEmitter.kt:1735` strips nullable; `analyzeConditionalInner` (`:1794`) falls to bare-scalar branch; no case composes `RemainingCmp` with LengthPrefixedString / ProtocolMessageScalar. |
| `@When @LengthPrefixed @UseCodec` with non-List field type (Scalar Payload) | silent-skip | low | — | `CodecEmitter.kt:1744-1747` falls through; payload case returns null at `:1838` invisibly to the conditional analyzer (validator rejects at `:1482`, so validator-stage gap only). |

---

### 2.4 Wire-order, nesting & value classes

#### Supported

| Shape | Evidence |
|-------|----------|
| `@ProtocolMessage(wireOrder)` with unsigned scalars (UByte/UShort/UInt/ULong) | `BigWirePacketCodec.kt` (swapBytes assembly, wireOrder=Big) |
| `@ProtocolMessage(wireOrder)` with Float/Double | `BigWirePacketCodec.kt:33-36` (`Float.fromBits()`/`Double.fromBits()` + byte-order swap) |
| `@ProtocolMessage(wireOrder)` with signed scalars at natural width | `BigWirePacketCodec.kt:17-31` |
| Signed scalars with explicit `@WireOrder` field override (natural width) | `CodecEmitter.kt:817`; batch fixtures (`MixedOrderFlush` UShort `@WireOrder(Little)`) |
| `@JvmInline value class` with single unsigned scalar | `OpCodeCodec.kt`, `SmallFlagsCodec.kt`, `WithFlagPayloadCodec.kt` |
| value class as `@DispatchOn` discriminator with `@DispatchValue` | `OpCodeCodec.kt`, `ForwardCompatibleOpCodec.kt:22`, `EtherTypeCodec.kt` |
| `@DispatchValue` return types Boolean/Byte/UByte/Short/UShort/Int/UInt | `CodecEmitter.kt:9451-9460` `DISPATCH_VALUE_RETURN_KINDS`; validator `:388` |
| Nested `@ProtocolMessage` data class field via `@LengthFrom` | `TlsHandshake.kt:30-35`, `TlsHandshakeCodec` (delegates to `TlsHandshakeBodyCodec`) |
| Nested `@ProtocolMessage` sealed parent field via `@LengthFrom` | `TlsHandshakeWithSealedBody.kt:65-68` |
| Nested sealed-interface field with `@PacketType` variants | `DeviceStateCodec.kt:16` (`ConnectionStatusCodec.decode()`), `CommandPayloadProtocol.kt` |
| Batch-encode coalescing of adjacent same-order unsigned scalars | `BigWirePacketCodec.kt:18-22,25-28`; `BatchCoalescingCodegenTest` |
| Batch coalescing respects per-field `@WireOrder` overrides | `MixedOrderFlush:119-123`; `CodecEmitter.kt:2871-2872` mismatch check |
| Batch coalescing respects value-class `@ProtocolMessage(wireOrder)` | `MixedOrderValueClass:134-138`; `CodecEmitter.kt:2813,2871` |
| `LePacket` value-class field + dotted `@LengthFrom` reference | `LePacket.kt` (LeHeader wireOrder=Little, `@LengthFrom("header.length")`) |

#### Rejected (with diagnostic)

| Shape | Diagnostic | Location |
|-------|-----------|----------|
| `@DispatchValue` returning Long or ULong | `@PacketType.value is Int and cannot address beyond Int.MAX_VALUE; return must be one of {Boolean,Byte,UByte,Short,UShort,Int,UInt}` | `ProtocolMessageProcessor.kt:388-397` |
| `@DispatchValue` returning nullable | `discriminator value must be non-nullable` | `ProtocolMessageProcessor.kt:388` |
| value class with `@WireBytes`/`@WireOrder` on outer parameter | `deferred; only bare natural-width field support and dotted @When in scope` | `CodecEmitter.kt:779-780` *(emitter-level)* |
| `@DispatchOn` variant first ctor param not discriminator type | `variant must declare first constructor parameter as the discriminator type` | `ProtocolMessageProcessor.kt:479-487` |
| `@DispatchValue` declared > once | `count must be exactly one; found N` | `ProtocolMessageProcessor.kt:362-368` |

#### Silent Gaps (Outcome 3)

| Shape | Outcome | Severity | Issue | Evidence |
|-------|---------|----------|-------|----------|
| Signed scalar with explicit `@WireOrder` override (non-natural narrowing) | silent-skip | **high** | #154 | `CodecEmitter.kt:817` returns null when `isSigned && wireBytes != width`. `WireOrderMismatchPackets.kt:35`: "Silently rejects signed scalars when paired with explicit wireOrder…no codec is generated." |
| Signed scalar with field-level `@WireOrder` alongside message-level wireOrder | silent-skip | **high** | #154 | `readFieldWireOrder(param)` (`:806`) triggers manual assembly; `:817` rejects; manual assembly lacks signed support. No compile error, no codec. |
| Nested `@ProtocolMessage` field without `@LengthFrom`/`@LengthPrefixed` framing | silent-skip | **high** | — | `CodecEmitter.kt:786` `analyzeBareProtocolMessageField` requires framing; bare nested returns null at `:738` (`if (!isTerminal) return null`). |
| value-class field on top-level message with `@WireBytes` | silent-skip | medium | — | `CodecEmitter.kt:779-780` `if (wireBytesAnn != null) return null`; "deferred to later slice" but no diagnostic. |
| Batch coalescing with mixed wire orders when order-mismatch detection incomplete | silent-broken-codegen | medium | #161 | `CodecEmitter.kt:2871-2872` gate compares only against the *first* field's order; an alternating Big-Little-Big run could mis-coalesce on a gate regression — syntactically valid, semantically byte-swapped. |
| `@DispatchOn` variant lacking discriminator first param (object/data-object case) | silent-broken-codegen | medium | #150 | `ProtocolMessageProcessor.kt:468-474` `if (isObjectVariant) continue` skips the first-param check; safe today (objects emit `Exact(0)`) but an asymmetry risk if mutable object state is added. |
| value-class scalar field with `@WireOrder` override on the parameter (value class already declares own wireOrder) | silent-skip | low | — | `CodecEmitter.kt:780` rejects parameter `@WireOrder`; the value class's internal wireOrder is honored but the redundant parameter annotation aborts silently without explaining. |

---

### 2.5 `return null` census (emitter analyze paths)

This axis catalogs the emitter's `analyze*` `return null` sites — the literal source of Outcome-3 silence.

#### Supported

| Shape | Evidence |
|-------|----------|
| data class with only UByte/UShort/UInt/ULong scalar fields | snapshot `DeviceStateCodec.kt` (105 simple-scalar codecs) |
| `@LengthPrefixed @ProtocolMessage` data class body (terminal-only) | `CodecEmitter.kt:738-757` |
| `@JvmInline` value class wrapping single supported scalar | `CodecEmitter.kt:835-880` `analyzeValueClassScalarField` |
| Sealed interface with simple `@PacketType` (no `@DispatchOn`) | `CodecEmitter.kt:6862-6925` `analyzeSealedDispatcher`; `ConnectionStatusCodec` |
| `@When("boolean")` optional field | `CodecEmitter.kt:1545-1710` |
| `@LengthPrefixed val: String` (any position) | `CodecEmitter.kt:720-731` |
| `@RemainingBytes val: String` or `List<@ProtocolMessage data class>` | `CodecEmitter.kt:599-637` |
| `@WireBytes(N)` on numeric scalar (1-8, N ≤ width) | `CodecEmitter.kt:806-823` |
| Sealed interface with `@DispatchOn` value-class discriminator | `CodecEmitter.kt:6930-7145` |
| Object singleton variant | `CodecEmitter.kt:226-238` |
| `@LengthFrom val: String` / `List<@ProtocolMessage>` / `@ProtocolMessage` w/ numeric sibling | `CodecEmitter.kt:894-916,989-1031,1047-1094` |

#### Rejected (with diagnostic)

| Shape | Diagnostic | Location |
|-------|-----------|----------|
| `@ProtocolMessage` on abstract/regular class | `not a data/value/sealed/object` (early return) | `CodecEmitter.kt:240,243` |
| Sealed data class | `use sealed dispatcher path, not data-class path` | `CodecEmitter.kt:244` |
| data class with `@DispatchOn` | `@DispatchOn only on sealed parents` | `CodecEmitter.kt:245`; validator `:146-149` |
| Empty parameter list | `no codifiable fields` | `CodecEmitter.kt:250` |
| value class with ≠ 1 ctor param | Kotlin rule | `CodecEmitter.kt:253` |
| Multiple bounding shapes | `at most one bounding field` | `CodecEmitter.kt:284` |
| `@LengthPrefixed @WireBytes` combination | `meaningless combination` | `CodecEmitter.kt:690` |
| `@LengthPrefixed` non-@ProtocolMessage non-String | `only String and @ProtocolMessage data-class bodies` | `CodecEmitter.kt:748-749` |
| `@UseCodec` on non-object target | `codec must be object` | `CodecEmitter.kt:1261,1341` |

> Note: several "Rejected" rows here are emitter-side `return null` **comments** rather than validator diagnostics (e.g. signed `@WireBytes` `:817`, `@LengthFrom`/`@LengthPrefixed` terminal-only `:286-287`, `@LengthPrefixed @ProtocolMessage` non-terminal `:738`). Where no paired validator diagnostic exists, these are **actually Outcome 3** and appear in the Silent Gaps below.

#### Silent Gaps (Outcome 3)

| Shape | Outcome | Severity | Evidence |
|-------|---------|----------|----------|
| Unknown/typo annotation on field (`@WireOrdr`, custom `@MyCodec`) | silent-skip | **high** | `CodecEmitter.kt:591` `else -> return null` — no dispatcher case for unknown annotation; whole codec dropped, no diagnostic. |
| `@RemainingBytes` on type not String / List<@ProtocolMessage> / Payload | silent-skip | **high** | `CodecEmitter.kt:632` returns null for non-List; validator retired scalar-list but no fallback diagnostic for other types. |
| value class with unsupported scalar inner (Float, Double, String, BigDecimal) | silent-skip | **high** | `CodecEmitter.kt:853` `SUPPORTED_SCALARS[innerQname] ?: return null` — no diagnostic. |
| value class with `@WireBytes`/`@WireOrder` on inner property | silent-skip | **high** | `CodecEmitter.kt:858-862` returns null; "deferred to later slice", no fallback. |
| value-class field with `@WireBytes`/`@WireOrder` | silent-skip | medium | `CodecEmitter.kt:779-780` returns null; no diagnostic. |
| `@UseCodec` on type neither supported scalar nor classifiable type | silent-skip | medium | `CodecEmitter.kt:1266` three-way fallthrough returns null; no diagnostic for malformed `@UseCodec` target. |
| Message declares `<P: Payload>` but no `@RemainingBytesPayload` field consumes it | silent-skip | medium | `CodecEmitter.kt:349-355` returns null; comment claims "validator surfaces diagnostic" but validator does not for this path. |
| Nullable field without `@When` | silent-skip | low | `CodecEmitter.kt:597` `if (type.isMarkedNullable) return null` — no diagnostic for the omission. |
| Non-terminal `@RemainingBytes` with variable-size trailer | silent-skip | low | `CodecEmitter.kt:306-318` returns null — *legitimately paired* with validator diagnostic `:535`. |

---

### 2.6 Validator coverage census

This axis is the mirror of §2.5: what the **validator** explicitly catches (the large, healthy Outcome-2 set) and where it is *silent* relative to the emitter.

#### Supported (validator accepts → emitter generates)

| Shape | Evidence |
|-------|----------|
| `@DispatchOn` sealed parent, single-byte discriminator | `validateDispatchOnSealed:291-490`; `TcpSegmentByFlags.kt` |
| Simple sealed dispatcher with `@PacketType(0..255)` | `validateSealedDispatcher:198-262` |
| `@When` on sibling Boolean with nullable field | `validateWhen:716-887`; `WithOptional.kt` |
| `@When` dotted form (value-class Boolean property) | `validateWhen:846-886`; slice14c fixtures |
| `@LengthFrom String` bounded by numeric sibling | `validateLengthFrom:1026-1191`; `RemoteHeader.kt` |
| `@LengthFrom List<@ProtocolMessage>` bounded by numeric sibling | `validateLengthFrom:1049-1061` |
| `@LengthFrom` nested `@ProtocolMessage` data class / sealed parent | `validateLengthFrom:1057-1061` (#151 pt 1) |
| `@RemainingBytes List<@DispatchOn sealed parent>` | `validateRemainingBytesElementType:620-650`; `MqttV3SubAckReturnCode` |
| `@RemainingBytes @UseCodec(Codec<Payload>)` | `validateUseCodec:1351-1372`; `BinaryData.kt` |
| `@UseCodec` bare scalar | `validateUseCodec` / emitter `:760-770`; `MqttRemainingLengthCodec` |
| `@LengthPrefixed @UseCodec(BoundingLengthCodec<UInt>) List<@ProtocolMessage>` | `validateLengthPrefixedUseCodec:1513-1569` |
| `@LengthPrefixed @UseCodec(Codec<Payload>)` scalar | `validateLengthPrefixedUseCodec:1463-1511`; `OwnedBytesHandle.kt` |
| `@FramedBy(BoundingLengthCodec<UInt>, after=…)` | `validateFramedBy:1646-1801`; slice14b/c |
| `@FramedBy` inherited by sealed variants | `detectFramedBy:428-444` |
| `@ForwardCompatible` + `@FramedBy` + `@DispatchOn` + `@UnknownVariant` | `validateForwardCompatible:1840-1974` |
| `<P: Payload>` on data class with `@RemainingBytes` field | `validatePayloadTypeParameter:1992-2064` |
| Raw-bytes type rejection in field declarations | `walkType:2104-2172`, `validatePayloadShape:2186-2281` |

#### Rejected (with diagnostic) — representative

The validator carries the bulk of the Outcome-2 diagnostics. Highlights (full set in the inventory):

| Family | Examples & locations |
|--------|----------------------|
| `@DispatchOn` discriminator well-formedness | not value class `:311`; ≠1 ctor param `:322`; nullable inner `:331`; non-numeric inner `:341`; missing/≠1 `@DispatchValue` `:363`; mutable `@DispatchValue` `:372`; bad return type `:391` |
| Dispatch variants | non-data/object `:415`; missing `@PacketType` `:431`; first param not discriminator `:481` |
| Simple dispatch | missing `@PacketType` `:225`; value out of 0..255 `:245`; duplicate value `:254` |
| `@When` | not nullable `:738`; bad grammar-2 `:765`; deep path `:780`; bad sibling `:807`; non-Boolean source `:837`; dotted non-value-class `:852`; dotted non-Boolean property `:877` |
| `@LengthFrom` | R1 adjacent `:996`; bad bound type `:1065`; deep path `:1079`; bad sibling `:1104`; non-numeric `:1135`; dotted non-value-class/non-Int `:1152` |
| `@RemainingBytes` | non-terminal + var trailer `:534`; primitive array `:607`; List<scalar> retired `:630` |
| `@WireBytes` | out of 1..8 `:674`; exceeds natural width `:687` |
| `@UseCodec` | generic-field exclusion `:1278`; Payload w/o `@UseCodec` `:1292`; + `@LengthFrom` `:1325`; Payload w/o `@RemainingBytes` `:1352`; non-Payload `@RemainingBytes @UseCodec` `:1363`; non-object target `:1382`; wrong `Codec<T>` `:1394` |
| `@LengthPrefixed @UseCodec` | non-object `:1441`; bad field type `:1483`; wrong `Codec<T>` `:1501`; not `BoundingLengthCodec<UInt>` `:1514`; element not `@ProtocolMessage` `:1545` |
| `@FramedBy` / `@ForwardCompatible` | E6 `:1671`; E1 `:1688`; E2 `:1765`; E3 `:1779/:1793`; E4 `:1733`; F1 `:1853`; F2 `:1871/:1899`; F3 `:1914/:1931`; F4 `:1941`; F5 `:1966` |
| Generics | multiple type params `:2000`; bound count ≠1 `:2012`; non-Payload bound `:2026`; declared-unused `:2053` |
| Raw-bytes hygiene | forbidden field type `:2120`; transitive Payload embed `:2290` |

#### Silent Gaps (Outcome 3) — validator-silent / emitter-rejecting

| Shape | Outcome | Severity | Evidence |
|-------|---------|----------|----------|
| `@LengthFrom`/`@LengthFromList`/`@LengthFromMessage` on non-terminal field | silent-skip | medium | Emitter `:286-292` returns null; `validateLengthFrom`/`validateRemainingBytesTrailers` have no terminal-position check. |
| Multiple `@UseCodec(BoundingLengthCodec)` fields in same message | silent-skip | **high** | Emitter `:284` returns null; validator never checks for multiple bounding fields. |
| Unknown/unrecognized annotations on fields | silent-skip | low | Emitter `:591` `else -> return null`; validator has no annotation whitelist. |
| Empty `@ProtocolMessage` data class | silent-skip | low | Emitter `:250` returns null; validator calls `tryEmit` unconditionally (`:193`). |
| `@When` on non-scalar inner (e.g. `@LengthPrefixed` message body in conditional) | silent-skip | medium | `validateWhen:716-887` doesn't restrict inner shapes; emitter defers composition (Slice 11a). |
| `@LengthPrefixed @ProtocolMessage` body on non-terminal field | silent-skip | medium | Emitter `:738` returns null; neither `validateLengthFrom` nor `validateRemainingBytesTrailers` checks terminal for `@LengthPrefixed` nested message. |
| `@LengthPrefixed @UseCodec(Codec<E>) List<E>` where E has `<P: Payload>` | silent-skip | medium | Validator `:1558-1569` covers resolved case; pre-slice gap if element generic param uninspected. |
| Nullable field type on non-`@When` parameter | silent-skip | **high** | Emitter `:597` returns null; validator has no explicit rejection of nullable-without-`@When`. |

---

## 3. Silent Gaps backlog (consolidated, severity-sorted)

Every Outcome-3 row across all axes, deduplicated and tagged with the recommended fix:

- **`reject-with-diagnostic`** — the shape is genuinely out of scope; add a validator error so the build fails loudly.
- **`add-support`** — the shape is reasonable and expected; implement it in the emitter.
- **`unify-dispatch`** — the gap exists *only* because the simple and `@DispatchOn` dispatch paths diverged; fixing it means merging the two paths (see §4).

### HIGH severity

| # | Shape | Axis | Recommendation | Notes |
|---|-------|------|----------------|-------|
| 1 | Signed multi-byte discriminators (Short/Long) in `@DispatchOn` | dispatch | `add-support` (#176) | Extend `peekableDispatcherInnerKinds`; add parallel signed peek paths. |
| 2 | Unsigned multi-byte (ULong) discriminators in `@DispatchOn` | dispatch | `add-support` (#176) | Same set, ULong peek path. |
| 3 | Generic `<P: Payload>` under **simple** `@PacketType` dispatcher | dispatch / genericity | `unify-dispatch` (#176) | Simple path has no `payloadTypeParameter`; merge with `@DispatchOn` path. |
| 4 | Generic sealed parent `<out P>` emitted as `object`, not `class<P>` | genericity | `unify-dispatch` (#176) | `buildSealedDispatcherFileSpec` always `object`. |
| 5 | Generic variant under non-generic simple sealed parent | genericity | `unify-dispatch` (#176) | Simple path lacks the `:7061` parent-generic check the `@DispatchOn` path has. |
| 6 | Generic sealed parent w/ `@PacketType` + `@FramedBy`, no `@DispatchOn` | genericity | `unify-dispatch` (#176) | Framed encode infra exists only on the `@DispatchOn` path. |
| 7 | `@LengthFrom` on value-class-wrapped scalar sibling (non-dotted) | length | `reject-with-diagnostic` or `add-support` (#163) | Validator passes value-class sibling; emitter rejects. |
| 8 | `@LengthPrefixed val: @ProtocolMessage` non-terminal | length | `reject-with-diagnostic` | Add terminal-position validator error. |
| 9 | `@LengthFrom on @ProtocolMessage` non-terminal | length | `reject-with-diagnostic` | Same terminal check. |
| 10 | `@LengthFrom on List<@ProtocolMessage>` non-terminal | length | `reject-with-diagnostic` | Same terminal check. |
| 11 | `@LengthPrefixedUseCodecList` w/ non-singleton (generic) element codec | length | `reject-with-diagnostic` | Inspect element generic param pre-slice. |
| 12 | `@When @UseCodec` on sealed parent without `@DispatchOn` | length | `reject-with-diagnostic` | Validator accepts; emits non-functional singleton call. |
| 13 | Multiple bounding fields in same message | length / validator | `reject-with-diagnostic` | Add validator multiple-bounding-field error mirroring emitter `:284`. |
| 14 | Signed scalar with explicit `@WireOrder` override (narrowed) | wireorder | `reject-with-diagnostic` or `add-support` (#154) | Manual assembly lacks signed support. |
| 15 | Signed scalar field-level `@WireOrder` + message-level wireOrder | wireorder | `reject-with-diagnostic` or `add-support` (#154) | Same root cause. |
| 16 | Nested `@ProtocolMessage` field without framing | wireorder / nesting | `reject-with-diagnostic` | Add "bare nested message needs @LengthFrom/@LengthPrefixed/@RemainingBytes" error. |
| 17 | Unknown/typo annotation on field | return-null | `reject-with-diagnostic` | Add annotation whitelist; `else ->` should emit error, not `return null`. |
| 18 | `@RemainingBytes` on non-String/List/Payload type | return-null | `reject-with-diagnostic` | Validator fallback for "other types". |
| 19 | value class with unsupported scalar inner (Float/Double/String/…) | return-null | `reject-with-diagnostic` | Validator error listing supported inner scalars. |
| 20 | value class with `@WireBytes`/`@WireOrder` on inner property | return-null | `reject-with-diagnostic` | Validator error (or `add-support` later). |
| 21 | Nullable field type on non-`@When` parameter | return-null / validator | `reject-with-diagnostic` | Validator error mirroring emitter `:597`. |

### MEDIUM severity

| # | Shape | Axis | Recommendation |
|---|-------|------|----------------|
| 22 | Data object variant under simple `@PacketType` w/ variable peekFrameSize | dispatch | `unify-dispatch` (unified peek walk) + add test coverage |
| 23 | Asymmetric wireSize (simple aggregates discriminator, `@DispatchOn` doesn't) | dispatch | `unify-dispatch` (single wireSize builder) |
| 24 | `@DispatchOn` framed generic variant + `@ForwardCompatible` round-trip | dispatch | `add-support` (thread `payloadCodec` into Unknown re-frame) + test |
| 25 | `Partial<P>` aggregator: generic variant under non-generic simple parent | genericity | `unify-dispatch` (#176) |
| 26 | `@LengthFrom` value-class property w/ non-peekable inner (ULong/Long) | length | `reject-with-diagnostic` or `add-support` (#163) |
| 27 | `@When @LengthPrefixed` on inner `@ProtocolMessage` (non-String) | length | `reject-with-diagnostic` or `add-support` |
| 28 | `@RemainingBytes @ProtocolMessage` (bare nested, not List) | length | `add-support` (#151) |
| 29 | `@When` field w/ remaining-bytes predicate but non-scalar inner | length | `reject-with-diagnostic` |
| 30 | Batch coalescing mixed wire orders w/ incomplete gate | wireorder | `add-support` / harden gate + regression test (#161) |
| 31 | `@DispatchOn` object variant skips first-param check | wireorder | `reject-with-diagnostic` (defensive) (#150) |
| 32 | value-class field with `@WireBytes` on top-level message | wireorder / return-null | `reject-with-diagnostic` |
| 33 | `@UseCodec` on unclassifiable type | return-null | `reject-with-diagnostic` |
| 34 | `<P: Payload>` declared but no `@RemainingBytesPayload` consumes it (non-data paths) | return-null | `reject-with-diagnostic` (extend validator to sealed parents) |
| 35 | `@LengthFrom*` on non-terminal (validator-silent) | validator | `reject-with-diagnostic` |
| 36 | `@When` on non-scalar inner (conditional message body) | validator | `reject-with-diagnostic` or `add-support` |
| 37 | `@LengthPrefixed @ProtocolMessage` non-terminal (validator-silent) | validator | `reject-with-diagnostic` |
| 38 | `@LengthPrefixed @UseCodec List<E>` E generic (pre-slice) | validator | `reject-with-diagnostic` |

### LOW severity

| # | Shape | Axis | Recommendation |
|---|-------|------|----------------|
| 39 | value-class scalar field w/ inner `@WireBytes`/`@WireOrder` | length | `reject-with-diagnostic` |
| 40 | `@When` w/ nullable predicate source (emitter-side) | length | `reject-with-diagnostic` (validator already covers) |
| 41 | `@When @LengthPrefixed @UseCodec` non-List (Scalar Payload) | length | already validator-rejected; close emitter gap |
| 42 | value-class field `@WireOrder` on parameter (value class has own wireOrder) | wireorder | `reject-with-diagnostic` (clarify message) |
| 43 | Nullable field without `@When` (return-null) | return-null | `reject-with-diagnostic` |
| 44 | Non-terminal `@RemainingBytes` w/ var trailer | return-null | already paired w/ validator `:535` — verify, then close |
| 45 | Unknown annotations (validator whitelist) | validator | `reject-with-diagnostic` |
| 46 | Empty `@ProtocolMessage` data class | validator | `reject-with-diagnostic` |

**Tally by recommendation:** the backlog skews heavily toward `reject-with-diagnostic` (validator gaps) and `unify-dispatch` (the two-path drift). The `add-support` items cluster on #176 (generics through simple dispatch), #154 (signed `@WireOrder`), #163 (value-class length siblings), and #151 (bare nested `@RemainingBytes`).

---

## 4. Architecture implications

The Outcome-3 backlog is not a random scatter of TODOs — it is the symptom of three structural decisions. Fixing the symptoms one row at a time will not stop new silent gaps from appearing; the structure has to change.

### 4.1 Two parallel dispatch paths drift

There are two independent sealed-dispatcher analyzers:

- `analyzeSealedDispatcher` (`CodecEmitter.kt:6862`) — simple `@PacketType`, emits an `object …Codec` singleton (`buildSealedDispatcherFileSpec:7246`).
- `analyzeDispatchOnSealedDispatcher` (`CodecEmitter.kt:6930`) — `@DispatchOn`, emits a `class …Codec<P>` when generic (`buildGenericDispatchOnDispatcherTypeSpec:7444`).

Features added to the `@DispatchOn` path were never back-ported to the simple path. The entire genericity column of the Silent Gaps backlog (#3–#6, #25) collapses to one fact: **`DispatcherShape` (simple) has no `payloadTypeParameter` field; `DispatchOnDispatcherShape` does (`:8258`).** Likewise the wireSize asymmetry (#23), the framed-encode-only-on-`@DispatchOn` gap (#6), and the peekFrameSize unified-walk gap (#22) are all "this path has it, that path doesn't."

**Direction:** collapse the two analyzers into one. A simple `@PacketType` dispatcher is just a `@DispatchOn` dispatcher whose discriminator is an implicit single-byte UByte value class with the identity `@DispatchValue`. Modeling it that way deletes the second path entirely and makes generics/framing/forward-compat *automatically* available to `@PacketType` users.

### 4.2 Validator and emitter independently re-analyze

`ProtocolMessageProcessor` (validator) and `CodecEmitter` (analyzer/emitter) each walk the same declarations with their own, hand-maintained notion of "supported." Outcome 3 is *definitionally* the set where `emitter.return-null ⊋ validator.diagnostics`. Every "validator passes, emitter rejects" row (#7, #8–13, #17–21, #33–38, #43–46) is one of these drift points.

**Direction:** make `analyze` the single source of truth. The emitter should run one **total** analysis pass producing a sum type:

```
sealed interface AnalysisResult
data class Supported(val shape: FieldShape /* or MessageShape */) : AnalysisResult
data class Rejected(val node: KSNode, val message: String) : AnalysisResult
```

`analyze` returns `Rejected` (never bare `null`) at every site that currently `return null`s. The KSP `process()` loop then has exactly one rule: **`Rejected` ⇒ emit a diagnostic; `Supported` ⇒ feed the emitter.** The validator's role shrinks from "re-derive support" to "render the `Rejected.message`." There is then no possible drift: a shape the emitter can't handle is, by construction, a diagnostic — Outcome 3 ceases to exist as a category.

### 4.3 Discriminator-as-sum-type makes feature dimensions orthogonal

Today, "generic payload," "forward-compatible," "framed," and "multi-byte/signed discriminator" are tangled together — supported only in the specific combinations that some in-scope protocol vector exercised (the `peekableDispatcherInnerKinds = {UByte, Byte, UShort, UInt}` set at `:2016` is literally "what the test vectors needed"). That is why #1/#2 (signed/ULong discriminators) and #24 (forward-compat × generic) are silent: nobody wrote a vector crossing those axes.

**Direction:** unify the two dispatch IRs around a discriminator modeled as a sum type over `{kind ∈ all 8 numeric scalars} × {peek strategy}`, with `generic payload`, `forward-compatible sink`, `framed`, and `varint width` as **orthogonal boolean/option properties** of the unified dispatcher shape rather than path-gated capabilities. Once the discriminator kind is a free dimension, signed/ULong discriminators (#1, #2) fall out for free; once generics are a property of the unified shape rather than the `@DispatchOn`-only class, #3–#6/#25 are solved; once forward-compat is orthogonal to genericity, #24's `payloadCodec` threading is a single code path.

### Summary

| Structural problem | Backlog rows it generates | Proposed direction |
|--------------------|---------------------------|--------------------|
| Two parallel dispatch paths | #3–#6, #22–#25 | Merge analyzers; simple `@PacketType` = implicit-discriminator `@DispatchOn` |
| Validator/emitter independent re-analysis | #7–#13, #17–#21, #33–#38, #43–#46 | Single total `analyze → AnalysisResult(Supported\|Rejected)` pass; `Rejected` always becomes a diagnostic |
| Feature dimensions tangled / vector-gated | #1, #2, #24, #26 | Discriminator-as-sum-type; generics/forward-compat/varint as orthogonal properties |

The end state: a single analyze pass over a unified dispatcher IR, where every unsupported shape is a typed `Rejected` carrying its own diagnostic message, and the only way to be unsupported is to be **loud**.
