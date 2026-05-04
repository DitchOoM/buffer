# Phase 9 reset (2026-04-29)

This commit reverts every Phase 9 change on `feature/directional-codec`
back to `676d53f` (the merge-base with `main` at branch-off). All 161
commits remain reachable in `git log` — this is a forward revert, not a
history rewrite. We can still `git show <sha>`, `git diff <sha>..`, or
cherry-pick from the prior tips. Older plan files in
`~/.claude/plans/phase-9-*.md` capture the technical details of what was
attempted.

## Why we're resetting

Phase 9 attempted to rewrite the codec processor in place across three
strategies (`accelerated` → `redesign` → `step-by-step`) over ~3 weeks
and 49 commits. The work landed real progress (typed `@Payload`
fan-out, `BodyLengthFraming`, value-class auto-detect, Steps 4-redo
through 7) but accumulated load-bearing hidden state in
`~/.m2/repository/com/ditchoom/buffer-codec-processor/4.3.0-SNAPSHOT/`:

- The Step 6 commit (`4afd204`) deleted the legacy emitter source files,
  but the Apr 28 jar already in mavenLocal still embedded the legacy
  bytecode.
- Step 7 ("consumer cutover, verified downstream") ran against that
  ghost jar — not against the post-Step-6 source.
- Step 8 design assumed Step 6/7 closed cleanly. Step 8.4 (Guard 3
  cross-version wire equality) surfaced two emitter bugs in the new
  pipeline. The 8.5/8.6 unblock attempt then republished from current
  source and proved the new emitter has additional gaps the ghost jar
  was masking — payload-variant `encodeBody`/`wireSizeBody` emission and
  cross-module `@DispatchOn` symbol resolution. That republish destroyed
  the Apr 28 jar; mqtt's `models-v4:compileKotlinJvm` is currently
  broken.

The pattern across the three Phase 9 strategies is the same: each
strategy *deletes the safety net before the replacement covers the same
shapes*, validates against incomplete fixtures, then discovers the gap
when a downstream consumer is actually exercised.

## What's preserved by this revert

Nothing on `feature/directional-codec` is destroyed. The full chain is
still here:

```
ea3dbb5 (last Phase 9 commit — Step 8.8 docs)
└─ ... 159 Phase 9 commits ...
└─ 676d53f (merge-base with main)
└─ <THIS REVERT COMMIT>
```

Everything reachable: 49 Phase 9 codec-processor commits, the
contract-inversion work (Steps 8.1/8.2/8.3/8.7/8.8), the Step 8.4 Guard
3 in mqtt, the design docs in `.claude/plans/phase-9-*.md`. We can
cherry-pick or replay any of it onto the new direction.

## What we're going for instead

A single-strategy rewrite with a tighter validation loop:

1. **One pipeline.** No legacy/new emitter coexistence. No
   `pipelineEligible()` gate. No SPI custom providers alongside
   `@UseCodec`. Pick the canonical path for each capability and delete
   the rest.

2. **Validation in `:buffer-codec-test`.** MQTT v4, MQTT v5 (including
   v5 properties + payload-typed publish), WebSocket frames, and RIFF
   live as test fixtures in `buffer-codec-test/src/commonMain`. The
   processor feedback loop is `./gradlew :buffer-codec-test:jvmTest` —
   no `publishToMavenLocal` cycle. mqtt and websocket repos become
   downstream *consumers* once the codec is solid; their cutover is the
   final step, not a load-bearing test rig in the middle.

3. **Zero copy end-to-end.** No `ByteArray` in production code paths or
   generated code. Enforcement: an allocation tracker hooked into the
   `:buffer-codec-test` harness, plus manual bytecode inspection on hot
   paths.

4. **`buffer-flow` integration baked in.** Every generated `Codec<T>`
   plugs into `Connection<T>` / `StreamMux<T>` via
   `StreamProcessor.peekFrameSize` from day one. Smoke tests cover the
   four protocols end-to-end through `buffer-flow`.

5. **Models bend to the codec, not the other way around.** The MQTT v4
   / v5 / WebSocket / RIFF wire **specs** are the fixed point. The model
   *classes* — Kotlin field types, nullability, value-class wrapping,
   sealed-tree shape, default values, even data-class-vs-data-object —
   are negotiable in service of letting the processor generate clean,
   zero-copy code. If a `data object` blocks a clean dispatcher, it
   becomes a `data class`. If a public API surface needs to change to
   match what the codec naturally produces, change it. Spec compliance
   is verified by byte-for-byte vector tests, not by preserving an
   existing Kotlin shape.

6. **Codec contract: payload-only.** `encode` writes payload bytes only;
   `wireSize` returns payload byte count only; `decode` reads to the
   pre-bounded buffer's natural delimiter. Framing is the framework's
   job, expressed via field annotations on the consumer
   (`@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, `@DispatchOn`).
   No self-bounding codecs, no `serialize()` parallel to the dispatcher.

## Locked decisions

The full design contract lives in `PHASE_10_DESIGN.md` (with derivation
history archived in `PHASE_10_DESIGN_NOTES.md`). Treat
that file as authoritative — this section is a one-line index so future
sessions can scan what's settled before reopening anything.

| # | Decision                                                                  |
|---|---------------------------------------------------------------------------|
| 1 | Codec interface split into `Encoder` / `Decoder` / `SuspendingDecoder` / `FrameDetector`, unioned as `Codec<T>`. |
| 2 | `WireSize` sealed: `Exact(bytes)` fast path, `BackPatch` default.          |
| 3 | `PeekResult` sealed: `Complete(bytes)`, `NeedsMoreData`, `NoFraming` default. |
| 4 | Open `DecodeException` / `EncodeException` base classes; protocol layers subclass and attach domain fields. |
| 5 | Sync = `buffer.setLimit()`, async = `parent.slice(N).use { }`; encode stays sync; pool/scope release is lexical. |
| 6 | `CodecContext` keeps immutable map-backed shape; adds `getOrDefault`, field-path tracking, direction-specific keys. |
| 7 | `DecodeKey` / `EncodeKey` / `CodecKey` interfaces; KSP enforces object-only implementations. |
| 8 | `@Payload` shape: empty marker interface + slot generics on sealed parent + per-slot SAMs with `Partial` receiver. |
| 9 | Wire-mirroring carve-out: redundant length carriers (fields whose only purpose is to bound a sibling) are expressed by `@LengthPrefixed` on the bounded field, not as constructor parameters. Checksums, magic numbers, and padding stay as constructor parameters. |
| 10 | Generated-code language: KotlinPoet via KSP `CodeGenerator`. Emit one file per `@ProtocolMessage` at `<source-package>/<MessageName>Codec.kt`, hooked through `Dependencies(aggregating = false, sourceFile)` for incremental compilation. Driven by Stage A. |
| 11 | Codec object placement: sibling top-level `object MyMessageCodec`, not `MyMessage.Codec` companion. Matches the Phase 10 hand-written reference codecs (`RiffChunkHeaderCodec`, `WavFmtBodyCodec`, `WavFmtChunkCodec`); keeps generated source out of the data-class declaration file and avoids companion-on-data-class equals/hashCode/copy noise. Driven by Stage A. |
| 12 | `peekFrameSize` emission rule: emit it whenever the frame size is statically determinable from the wire format (fixed-size sum, or leading `@LengthPrefixed` whose prefix is peek-readable). Default `PeekResult.NoFraming` only when the wire format genuinely doesn't allow it. Strictly more capability than the hand-written reference codecs (which omit it on bodies that aren't typical stream roots) — no test or doctrine row contradicts this. Driven by Stage A. |
| 13 | `@WireOrder` and `@WireBytes` stay separate. They encode different concerns: `@WireOrder(Endianness)` is byte order for natural-width fields; `@WireBytes(N)` is wire width when narrower than the Kotlin type's natural size. Folding would force every field to declare both even when one is redundant. Bit-packed fixed-size headers (e.g., `MqttFixedHeader`, `MySqlPacketHeader`) are expressed as `@JvmInline value class`-of-raw with bit-shift getters in user code — no `@WireBytes` needed because the structure packs into a natural-width scalar. Driven by Stage B. |
| 14 | `LengthPrefix` enum stays at `Byte` / `Short` / `Int` — no widening. `Varint` belongs in a future `@VariableByteInteger` annotation (self-delimited, not fixed-width — fundamentally different shape); `Fixed(Int)` allows nonsense widths (e.g., `Fixed(7)`) and blocks compile-time validation of prefix range vs. body size. Three discrete widths cover every protocol surveyed (MQTT v3/v4/v5, WebSocket, RIFF, PNG, TLV). Slice-3 doctrine in `PHASE_10_DESIGN_NOTES.md`. Driven by Stage C. |
| 15 | `WireSize.BackPatch` is the encode strategy for `@LengthPrefixed val: String`. Pre-measure (two UTF-8 walks: size + encode) was rejected as measurably slower for the common short-string case; the runtime's `WriteBuffer.writeLengthPrefixedUtf8String()` already implements the BackPatch pattern (seek-forward, write body via `writeString`, patch prefix from position delta). The emitter inlines the same pattern for `Byte`/`Short`/`Int` prefix widths. First stage to actually produce `BackPatch` (Locked Decision row 2). Driven by Stage C. |
| 16 | Zero-`ByteArray` enforcement is JVM-scoped and JFR-tracked. The codec-emitter contract is "zero `[B` allocations attributable to encode/decode on JVM"; mechanism is a JFR allocation tracker (`jdk.ObjectAllocationInNewTLAB` + `jdk.ObjectAllocationOutsideTLAB`) hooked into `:buffer-codec-test:jvmTest`. The runtime's `WriteBuffer.writeString` is zero-`ByteArray` on JVM / Apple / JS but allocates one internal `ByteArray` per call on WASM and the `nonJvm`/`ByteArrayBuffer` path (`text.toString().encodeToByteArray()` then `writeBytes`). That gap is a known runtime-side limitation, not a codec-emitter regression — closing it is a separate runtime task (likely a Charset-aware streaming encoder). Driven by Stage C. |
| 17 | Unknown-discriminator behaviour for simple `@PacketType` sealed dispatch is fail-loud: the generated dispatcher throws `DecodeException` from both `decode` and `peekFrameSize` when the discriminator byte matches no variant. Exception fields mirror the existing emitter pattern (`CodecEmitter.kt` overflow-guard call site): `fieldPath = "<SealedParentSimpleName>.discriminator"`, `bufferPosition = <position before the discriminator was read>`, `expected = "one of {0xNN, 0xNN, …}"` (variants sorted ascending by `@PacketType.value`, two-digit hex), `actual = "0x<NN>"` (two-digit hex of the unmatched byte). No default-lambda escape hatch — the variant set is closed by the sealed hierarchy, so option (1) "fail-loud, no escape hatch" beats option (2) "default lambda the consumer overrides" (that pattern is Stage H's `@Payload` machinery, not Stage D's). Peek-time unknown discriminator is not recoverable by reading more bytes, so it throws the same exception rather than returning `PeekResult.NoFraming`; `NoFraming` remains reserved for protocols whose wire format genuinely can't be peeked. Driven by Stage D. |
| 18 | `@LengthFrom("fieldName")` resolution form: String DSL validated by KSP at compile time. The resolver shared with `@WhenTrue` (row 19) walks the bound field's owning constructor parameter list, looks up `fieldName`, and errors if the name is absent, declared at-or-after the bound field's position, or resolves to a non-numeric type. Property references (`MyMsg::fieldName`) and method references were rejected — Kotlin annotations cannot accept `KProperty1<T, *>` or function references (JVM annotation-parameter rules), so the only type-safe alternatives would be (a) `KClass`-of-predicate with one class per reference (verbose, doesn't read sibling fields cleanly, abandons the annotation-on-data-class shape), (b) a separate non-annotation DSL spec file (architectural split — every other codec feature lives on the data class), or (c) a custom compiler plugin (order-of-magnitude more work, separate maintenance burden). KSP-validated strings are the lightest type-safe substitute available without a compiler plugin: refactor-renames break compile with a "field doesn't exist" diagnostic naming valid alternatives — type-safe in the no-bad-code-reaches-runtime sense; IDE-fluent rename support deferred to any future compiler-plugin investment. Field-type universe for `@LengthFrom` in Stage E is `String` only (parity with Stage C's `@LengthPrefixed val: String`); `ByteArray`, nested `@ProtocolMessage`, and `@Payload`-typed terminals defer to later stages. Adjacent-`@LengthFrom` already errors via R1; row 18 covers the non-adjacent path the emitter now respects. Driven by Stage E. |
| 19 | `@WhenTrue("expression")` source kinds, placement, and runtime semantics. **Source kinds:** sibling `Boolean` field, **or** `<sibling>.<property>` where `<sibling>` is a `@JvmInline value class` exposing a `Boolean`-returning `val` property. Anything else — non-Boolean source, sibling that isn't a value class, deeper-than-one-level path, property that itself takes parameters — is a compile error naming the offending parameter and the available `Boolean`-returning properties on the resolved type. **Placement:** bound field must be `T?`. The slot's underlying type universe is anything Stages A/B/C/D already emit (scalars including `UByte`/`UShort`/`UInt`/`ULong`, `@WireBytes`, `@WireOrder`, `@LengthPrefixed val: String`, `@LengthPrefixed @ProtocolMessage`). No constraint on the constructor default expression — KSP cannot inspect default expression trees (only `KSValueParameter.hasDefault: Boolean` is exposed), so any "must default to `null`" rule cannot be enforced and is therefore not part of the contract; the kdoc softens to "nullability required, default unconstrained" in the slice that consumes this row. **Encoder semantics:** predicate-false ⇒ skip the entire slot (zero bytes on the wire, including any `@LengthPrefixed` prefix). Predicate-true with field == `null` ⇒ runtime `EncodeException` with field-path attribution per row 20. **WireSize:** any `@WhenTrue` field collapses the message-level `WireSize` to `BackPatch`; the emitter does not attempt conditional-`Exact` arithmetic. **peekFrameSize:** when the source is reachable statically (lies before any variable-length field — including being inside a leading value class), peek follows decode order, resolves the boolean, and walks. When the source lies past any variable-length field, the validator hard-errors at compile time — the case is unreachable for stream framing and a silent `NoFraming` fallback was rejected as hard to discover (a streaming consumer would see a stalled connection rather than a compile error). Driven by Stage E. |
| 20 | Field-path attribution for runtime `EncodeException` / `DecodeException` is compile-time string-literal concatenation, not a runtime path stack. Generated code passes `fieldPath = "<OwnerSimpleName>.<fieldName>"` (or `"<Owner>.<field>.<property>"` for value-class predicate sources) as a literal at the throw site — the same shape Stages A/B/C/D already use for overflow guards, length-prefix mismatches, and the row-17 unknown-discriminator throw. Zero `[B` allocations on JVM, KotlinPoet-friendly (constant-string interpolation), and paths are statically known at emit time. Explicitly rejects the deferred-decisions-table sketch ("`PathContext` facet pushed/popped through nested codec calls") — that approach allocates a new immutable map per field on the row-6 `CodecContext` and would violate row 16's zero-`[B` JVM contract. The motivating case for a runtime path stack would be attaching a path to a runtime-thrown exception originating from a deeper nested codec; Stage E's exceptions are all locally-thrown with statically-known field paths (the conditional-field decode failure point lives in the *outer* codec, not the inner one), so no runtime mechanism is needed. Driven by Stage E. |

## Deferred decisions

These were on the queue but never reached a final lock. Resolve each
inside the stage that first exercises it (column 3) — driving the
decision from a concrete vector beats deciding in the abstract.

| Topic                                          | Sketch / current leaning                                                  | Driven by stage |
|------------------------------------------------|---------------------------------------------------------------------------|-----------------|
| `@UseCodec` `expect`/`actual` resolution path  | Direct call to `expect` object, linker resolves; KSP doesn't inspect actual | Stage H         |
| `data object` vs empty `data class`            | `data class` for dispatcher cleanliness per item 5 above                   | Stage F         |
| Decompose `@RemainingLength` into format + behavior | Slice 8's `@RemainingLength` is MQTT v3.1.1 §2.2.3-shaped: 7-bit + continuation bit, capped at 4 bytes (max ~268M), AND implicitly sets `buffer.limit` to bound subsequent decode. The byte format is identical to MIDI variable-length quantities and is a constrained subset of LEB128 (Protobuf/WASM/Avro), which allow unbounded byte length and add zig-zag for signed types. The implicit-bounding behavior is MQTT-specific. **Followup before merging the PR**: decompose into orthogonal annotations — `@VarByteInt(maxBytes = N)` for the format and `@BoundsRemaining` for the buffer-limit side effect. MQTT's existing pairing becomes `@VarByteInt @BoundsRemaining val remainingLength: UInt`; non-MQTT protocols can use `@VarByteInt` alone (MIDI VLQ, Protobuf-style varints if max is widened). Decision deferred until a second var-int-using vector lands so `@VarByteInt`'s parameter set isn't designed in the abstract. | Stage G follow-up |
| Value-class field wireOrder propagation | `FieldSpec.ValueClassScalar` (slice 3) currently doesn't carry the value class's `@ProtocolMessage(wireOrder)`. The slice 6 dispatcher emits with byte order = buffer's runtime order (correct for tests that set BIG_ENDIAN); slice 6.5 added wireOrder to `DispatchOnDispatcherShape` for the dispatcher peek path, and slice 9 stores `valueClassInnerKind` in `LengthSource.ValueClassProperty` for the @LengthFrom dotted-form peek. Neither propagates the wireOrder through to the sequential walk's value-class peek-stash, which defaults to big-endian. Works correctly for big-endian protocols (HTTP/2, MQTT) but a little-endian protocol with a multi-byte value-class field referenced by a later `@WhenTrue("sibling.property")` or `@LengthFrom("sibling.property")` would assemble the peek-side bytes wrong. **Followup before merging the PR**: thread `wireOrder` through `FieldSpec.ValueClassScalar` and the slice 5a sequential peek's value-class-field handler (`appendPeekFixedScalar` already accepts the parameter — slice 6.5 wired it for the dispatcher; sequential walk just needs to pass it through). Add a little-endian dispatcher fixture to drive the change. | Stage G follow-up |

## Stages A–H — execution plan

Each stage adds one capability to the processor, gated on a vector test
in `:buffer-codec-test:jvmTest` going green. Stages stack: H exercises
everything below it. Stage 0 below comes first — it removes the existing
emitter so the processor is reduced to scaffolding.

Vector files live in
`buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/`,
tests in `…/commonTest/kotlin/com/ditchoom/buffer/codec/test/`. Each
stage lands as one commit (or a short series); CI for the stage is
`:buffer-codec-test:jvmTest` green plus any new processor unit tests in
`:buffer-codec-processor:test`.

### Stage 0 — Strip the processor

One commit. Delete every emitter file, all SPI wiring (the entire
`buffer-codec-test-spi/` module disappears, including its `include` in
`settings.gradle.kts`), all generated-codec snapshot tests, and every
fixture in `:buffer-codec-test` that depends on the emitter (so
`:buffer-codec-test` is intentionally broken until Stage A restores it).
**Keep:** the KSP entry point (`ProtocolMessageProcessor` +
`ProtocolMessageProcessorProvider`), the annotation surface in
`buffer-codec/src/commonMain/.../annotations/`, the runtime types
(`Codec`, `CodecContext`, `PayloadReader`). Update locked-decision
shapes (`WireSize`, `PeekResult`, split `Encoder`/`Decoder`/
`SuspendingDecoder`/`FrameDetector`, direction-specific keys) at the
same time so the runtime API matches PHASE_10 the moment a codec gets
emitted again.

After Stage 0: `:buffer-codec:check` and `:buffer-codec-processor:check`
stay green; `:buffer-codec-test:jvmTest` reports `NO-SOURCE` (the
fixture and test source sets are empty until Stage A puts a Heartbeat
vector + test back).

### Stage A — Single fixed-size scalar

- **Vector:** `data class Heartbeat(val timestamp: Long)`; on the wire =
  8 big-endian bytes.
- **Capability:** `@ProtocolMessage` → emit `object HeartbeatCodec :
  Codec<Heartbeat>` with `WireSize.Exact(8)` and `peekFrameSize`
  returning `PeekResult.Complete(8)`.
- **Locks deferred:** generated-code language (KotlinPoet), companion
  placement.
- **Acceptance:** byte-exact round-trip on a hand-crafted vector;
  `wireSize` matches; `peekFrameSize` returns Complete(8); KSP errors
  if the annotated class is not a data class.

### Stage B — Multi-scalar with endianness and custom widths

- **Vector:** DNS header (six fields, all big-endian) plus a
  BLE-ATT-style 3-byte little-endian length field.
- **Capability:** field sequencing across multiple scalars; `@WireOrder`
  per-field overrides the message default; `@WireBytes(N)` for
  custom-width little-endian fields. `WireSize.Exact` is the static sum.
- **Locks deferred:** `@WireOrder` + `@WireBytes` consolidation
  (resolved by whatever shape the emitter naturally produces).
- **Acceptance:** byte-exact vectors for both pure-BE and mixed-endian
  shapes; out-of-range `@WireBytes` literal is a compile error.

### Stage C — Length-prefixed variable terminal field

- **Vector:** `data class SimpleHeader(val id: Int, @LengthPrefixed val
  name: String)` — fixed prefix + UTF-8 body.
- **Capability:** `BackPatch` path. Encode uses `GrowableWriteBuffer`
  and patches the length prefix after the body is written; decode reads
  `len`, then exactly `len` UTF-8 bytes; codec defaults to
  `WireSize.BackPatch`.
- **Locks deferred:** `LengthPrefix` enum shape (`Byte`/`Short`/`Int`/
  `Varint`); zero-`ByteArray` enforcement (allocation tracker introduced
  here).
- **Acceptance:** round-trip across empty / ASCII / multi-byte UTF-8;
  allocation tracker confirms zero `ByteArray` allocations on the hot
  path.

### Stage D — Simple sealed dispatch with `@PacketType`

- **Vector:** `sealed interface Command { @PacketType(0x01) data class
  Ping(val ts: Long); @PacketType(0x02) data class Echo(@LengthPrefixed
  val msg: String) }`.
- **Capability:** 1-byte discriminator read/write; exhaustive `when` in
  the generated dispatcher; `peekFrameSize` peeks discriminator + the
  variant's frame.
- **Acceptance:** both variants round-trip byte-exact; duplicate
  `@PacketType` is a compile error; `peekFrameSize` correct across
  variants.

### Stage E — `@LengthFrom` + `@WhenTrue` conditional fields

- **Vector:** MQTT v3 CONNECT — flags byte drives optional will,
  username, password fields, each `@LengthPrefixed` UTF-8.
- **Capability:** cross-field length references via
  `@LengthFrom("siblingField")`; boolean-DSL conditional inclusion via
  `@WhenTrue("flags.willFlag")`; processor enforces that any non-terminal
  variable-length field has a length source.
- **Locks deferred:** `@LengthFrom` resolution form, `@WhenTrue` DSL
  surface, field-path tracking mechanism.
- **Acceptance:** round-trip across every combination of optional flags;
  missing length source on a non-terminal variable field is a compile
  error; bad path in `@WhenTrue` is a compile error with field-path in
  the message.

### Stage F — `@DispatchOn` with value-class discriminator

- **Vector:** MQTT v3 control packet sealed parent +
  `@JvmInline value class MqttFixedHeader(val raw: UByte)` carrying
  `@DispatchValue val packetType: Int`. Variants: Connect (built in
  Stage E), PingReq, Disconnect, plus one or two more.
- **Capability:** bit-packed value-class discriminator as the dispatcher
  source; `wire` value validated against the inner type's range at
  compile time; `peekFrameSize` flows through value-class dispatch.
- **Locks deferred:** `data object` vs empty `data class` for body-less
  variants (resolves to `data class` per item 5 above).
- **Acceptance:** full v3 packet set round-trips; out-of-range `wire`
  is a compile error; `peekFrameSize` returns `Complete` for all
  variants when given enough bytes.

### Stage G — Variable-length list of nested `@ProtocolMessage`

- **Vector:** MQTT v5 property list — self-length-prefixed list where
  each entry has a 1-byte type id and a typed value (Int / String /
  binary / k-v string pair).
- **Capability:** `List<NestedMessage>` field, per-entry dispatch on
  the list element's discriminator, list-level length prefix using
  `BackPatch`. Validates that the list-of-nested machinery composes
  with everything from A–F.
- **Acceptance:** round-trip a v5 SUBSCRIBE or CONNECT (whichever has
  the simpler property set) including empty / single / multi property
  lists.

### Stage H — Payload SAM via MQTT v5 PUBLISH

- **Vector:** full MQTT v5 PUBLISH —
  `MqttFixedHeader` (QoS bits matter), `@LengthPrefixed topic`,
  `@WhenTrue("header.qos > 0") packetId: PacketId?`,
  `properties: List<Property>`,
  `payload: Pub` (where `Pub : Payload`).
- **Capability:** full Section 8 machinery — emit
  `PublishCodec.Partial`, `PayloadDecoder<P>` SAM with `Partial`
  receiver, `PayloadEncoder<P>`, `MqttControlPacketCodec.decode`
  aggregator with throwing-default lambdas across every
  payload-bearing variant, and `MqttCodec` convenience alias. Locks
  the `@UseCodec` `expect`/`actual` resolution path: emitted code
  calls the `expect` codec object directly; the linker picks the
  platform actual.
- **Acceptance:**
  1. typed-payload PUBLISH (consumer-defined `JpegFrame : MyPub` via
     `@UseCodec(JpegImageCodec::class)`) round-trips byte-exact;
  2. bare-`Payload` escape hatch (#8.14 in PHASE_10) decodes and
     re-encodes without sealed exhaustiveness;
  3. throwing-default fires with the expected field-path message when
     an unexpected packet type arrives;
  4. `:buffer-flow` smoke test pushes PUBLISH frames through
     `Connection<MqttControlPacket<…>>` and pulls them out the other
     side without any `ByteArray` allocations on the hot path.

## Non-goals

- Bug-for-bug compatibility with the legacy emitter's output shape.
  Generated code can change as long as wire format stays correct.
- Preserving the 161-commit narrative as separate commits on the new
  direction — squash-and-merge at the end is fine.
- Touching mqtt or websocket repo histories. They stay frozen until the
  codec is solid; then they get from-scratch cutover commits.

## Reference: where the prior work lives

- `~/.claude/plans/phase-9-*.md` — every prior plan, blocker, handoff,
  and postmortem.
- `git log 676d53f..ea3dbb5` — the 161 commits, fully intact.
- mqtt's `feature/v2-api-cleanup` HEAD `28872f8b` — Step 8.4 Guard 3
  test (currently `@Ignore`d on bugs that no longer apply).
- websocket's `feature/v2-api-cleanup` HEAD — unchanged, still on the
  legacy codec path.

## Constraints (carryover)

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- mavenLocal `~/.m2/repository/com/ditchoom/buffer-codec-processor/4.3.0-SNAPSHOT/`
  currently holds a stale jar that doesn't satisfy mqtt. mavenLocal
  republish is post-rewrite, not now — mqtt and websocket cutovers are
  the final step after Stage H, not load-bearing during the rewrite
  (item 2 above).
- The cherry-pick replay plan from this file's prior "Next steps"
  (Steps 8.1/8.2/8.3/8.7/8.8) is superseded by the Stages A–H plan
  above. The contract-inversion lands naturally inside Stage 0 +
  early stages — do not attempt a separate replay.
