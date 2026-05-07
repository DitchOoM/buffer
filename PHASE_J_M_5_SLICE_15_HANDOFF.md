# Phase J.M.5 — slice 15 handoff (binary Payload slots + impossible-state sweep)

**Read this top-to-bottom before writing code.** Slice 14c proper landed
the `@FramedBy`-on-sealed-parent retrofit for both v3 and v5 (test
counts 490 / 68 / 36 at HEAD). This handoff covers the v5 binary-data
fields the original audit handoff deferred (CONNECT will-payload +
password, plus the two property variants `CorrelationData` and
`AuthenticationData`) and the impossible-state sweep that should land
before slice 15 so it doesn't have to re-model the same fields.

The session that produced this handoff ran a focused read-only audit
of the v5 fixtures against the MQTT v5.0 spec; the gap list is captured
in the FIX-TODAY / NEEDS-EMITTER-WORK / DEFER-TO-SLICE-15 tables below.
That audit confirmed the design simplification: **the four deferred
binary-data fields all want the same wire shape (v5 §1.5.6 Binary Data,
`<UShort BE length><bytes>`), so slice 15 reduces to one new emitter
capability + four fixture-side changes. No multi-type-param dispatcher.**

## Branch state at start of slice 15

Branch HEAD is the slice 14c proper commit. Test baseline:

```
:buffer-codec-test:jvmTest    = 490
:buffer-codec-processor:test  = 68
:buffer-flow:jvmTest          = 36
```

ktlint clean across `:buffer-codec-test`, `:buffer-codec-processor`,
and `:buffer-flow`. Cross-target compile clean (linuxX64 / js / wasmJs).

## Decisions locked before this handoff

These were walked with the user; do NOT re-litigate without strong
new evidence:

- **D1 — Typed Payload slots, not raw bytes.** `@ProtocolMessage` field
  sites cannot use `ByteArray`, `ReadBuffer`, `WriteBuffer`, or
  `PlatformBuffer` directly. Binary data crosses the codec boundary as
  a typed value implementing the `Payload` marker, with ownership
  semantics owned by the user-supplied `Codec<T>`. Rationale: raw
  buffer/bytes types in protocol message data classes leak ownership
  ambiguity (who frees, when, aliased?). The user must wrap bytes in
  a typed value class, write a `Codec<T>`, and reference it via
  `@UseCodec` so copy-vs-zero-copy is an explicit codec-author choice
  at one well-defined boundary.

- **D2 — `Payload` marker required on `@LengthPrefixed @UseCodec`
  slots.** Stricter shape: the field type T must implement
  `com.ditchoom.buffer.codec.Payload`. Clusters the "typed binary
  data crossing the codec boundary" concept under one marker so the
  codebase reads consistently with the existing
  `@RemainingBytes val payload: P : Payload` slice-10b/10d shape.

- **D3 — Test fixture defines a new `BinaryData` typed wrapper.** Slice
  10b/10d's `JpegImage` / `TextPayload` types don't exercise the
  opaque-binary-bytes wire shape cleanly (`JpegImage` is structured;
  `TextPayload` is `String`-backed). New minimal wrapper:
  ```kotlin
  @JvmInline value class BinaryData(val bytes: ByteArray) : Payload
  object BinaryDataCodec : Codec<BinaryData> {
      override fun decode(buffer, ctx): BinaryData =
          BinaryData(buffer.readByteArray(buffer.remaining()))
      override fun encode(buffer, value, ctx) {
          buffer.writeBytes(value.bytes)
      }
      override fun wireSize(value, ctx): WireSize.Exact = WireSize.Exact(value.bytes.size)
  }
  ```
  Real consumers (downstream MQTT library authors) define their own
  typed wrapper + codec. The test fixture's `BinaryData` is purely
  test-infrastructure.

## Audit gap list (impossible-state findings)

### FIX-TODAY — single audit-2f sweep before slice 15

These are init-block requires (mostly) plus one value-class invariant.
Single commit, no emitter changes. Pre-slice-15 ordering is deliberate:
slice 15 will touch some of the same variants and we don't want to
double-fix.

1. **`MqttV5Packet.Unsubscribe`** — `init { require(topics.isNotEmpty()) }`
   per §3.10.3 [MQTT-3.10.3-2]. Mirrors the existing `Subscribe`
   guard.
2. **`MqttV5Packet.SubAck`** — `init { require(reasonCodes.isNotEmpty()) }`
   per §3.9.3.
3. **`MqttConnectFlags`** — reserved bit 0 must be zero per §3.1.2.3
   [MQTT-3.1.2-3]. Affects v3 + v5 (shared value class). Add init-block
   require.
4. **`MqttConnectFlags.willQoS`** — must not be 3 per §3.1.2.6
   [MQTT-3.1.2-13/14]; if `!willPresent`, willQoS must be 0. Init-block.
5. **`MqttPacket.Connect` (v3 only)** — `!usernamePresent →
   !passwordPresent` per [MQTT-3.1.2-22]. v5 dropped this rule
   (§3.1.3.5). Put it on the v3 Connect `init { ... }` block, NOT in
   the shared `MqttConnectFlags` class.
6. **`MqttV5Packet.ConnAck.connectAckFlags`** — bits 7-1 must be zero
   per §3.2.2.1 [MQTT-3.2.2-1]. Either init-block require or a
   `V5ConnAckFlags` value class (mirroring `V5SubscriptionOptions`).
   Init-block is the smaller diff; defer the value class to a later
   doctrine sweep.
7. **`MqttV5Property.ReceiveMaximum`** — `init { require(value > 0u) }`
   per §3.1.2.11.3 [MQTT-3.1.2-32].
8. **`MqttV5Property.MaximumPacketSize`** — `init { require(value > 0u) }`
   per §3.1.2.11.4 [MQTT-3.1.2-31].
9. **`MqttV5Property.TopicAlias`** — `init { require(value > 0u) }` per
   §3.3.2.3.4 [MQTT-3.3.2-8].
10. **`PacketId`** — value-class invariant `init { require(raw > 0u) }`
    per §2.2.1 [MQTT-2.2.1-3]. Closes the nonzero-packet-id rule
    centrally for all 9 variants that carry one.

### NEEDS-EMITTER-WORK — init-block fallback in audit-2f, deeper fix later

1. **PUBLISH header low-nibble — forbid QoS=3** per §3.3.1.2
   [MQTT-3.3.1-4]. Tighten the `MqttFixedHeader` init-block to require
   `(raw and 0b00000110) != 0b00000110` when `packetType == 3`.
   Cleaner future fix is a typed `V5PublishFlags` companion to
   `MqttFixedHeader.flags` — defer.
2. **PUBLISH `packetId` cross-bit invariant** —
   `header.qosGreaterThanZero == (packetId != null)` per §2.2.1.
   Init-block on `MqttPacket.Publish` and `MqttV5Packet.Publish`. The
   `@When` framework currently tolerates set-when-skipped; an emitter
   tightening would be the cleaner fix but the init-block closes the
   gap today.

### DEFER-TO-SLICE-15 — slice 15 shapes these

These ARE slice 15:

- **`Connect.willMessage` (v3 + v5)** → typed Payload slot per §3.1.3.3.
- **`Connect.password` (v3 + v5)** → typed Payload slot per §3.1.3.5.
- **`MqttV5Property.AuthenticationData`** (id 0x16, §3.1.2.11.10) — new variant.
- **`MqttV5Property.CorrelationData`** (id 0x09, §3.3.2.3.6) — new variant.
- **Cross-property uniqueness** (§3.1.2.11/§3.2.2.3/etc.) — most
  properties are "Protocol Error to include more than once" except
  UserProperty + SubscriptionIdentifier. Decision deferred to slice
  15e (optional); see roadmap.

### Uncertain — verify before fixing

- **PUBLISH retain-with-QoS0-DUP** (§3.3.1.1 [MQTT-3.3.1-2]): DUP MUST
  be 0 for QoS 0. Whether to enforce in init-block (rejecting
  `MqttFixedHeader(0x38u)` etc.) is a doctrine call. Audit-2f or a
  follow-up; not a slice 15 blocker.

## Implementation roadmap

### audit-2f — impossible-state sweep (lands BEFORE slice 15)

Single commit (or a few small commits if the diff balloons). Each
finding from the FIX-TODAY + NEEDS-EMITTER-WORK init-block-fallback
buckets above lands as an `init { require(...) }` (or a value-class
invariant for `PacketId`). Test delta:

- Existing tests stay green (every fixture construction in the test
  suite already satisfies the new invariants — they're consistent
  with the spec).
- Add ~6-10 negative-construction tests that assert `IllegalArgumentException`
  on out-of-spec construction. One per non-trivial invariant (the
  "value > 0u" trio can share a parameterized test).

Verify: `:buffer-codec-test:jvmTest :buffer-codec-processor:test
:buffer-flow:jvmTest`, ktlint clean, cross-target compile clean.
Expected count: 490 + N where N = number of negative-construction
tests added (target ~10).

### Slice 15a — `@LengthPrefixed @UseCodec` for typed scalars

**New emitter capability.** Today the codec processor recognizes:

- `@LengthPrefixed val foo: String` (slice 5a, built-in string emit)
- `@LengthPrefixed @UseCodec(...) val foo: List<...>` (slice 11,
  list with custom prefix codec)

Slice 15a adds the **scalar** counterpart of slice 11:

- `@LengthPrefixed @UseCodec(TCodec::class) val foo: T` where
  `T : Payload` per D2.

Wire: 2-byte UShort BE prefix (matching the existing `@LengthPrefixed
val foo: String` shape) + bytes consumed by `TCodec`. Decode: read
prefix, narrow `buffer.limit()` to position+length, run `TCodec.decode`,
restore limit. Encode: back-patched UShort prefix + body via
`TCodec.encode` (same back-patch shape as `@LengthPrefixed val foo:
String`).

**Probe-first** (mandatory — same lesson 14c-prep enforced for the
generic dispatcher): land a `slice15a/` fixture before touching v5.

```kotlin
// buffer-codec-test/.../slice15a/Slice15aLengthPrefixedPayload.kt
@JvmInline value class BinaryData(val bytes: ByteArray) : Payload
object BinaryDataCodec : Codec<BinaryData> {
    override fun decode(buffer, ctx): BinaryData =
        BinaryData(buffer.readByteArray(buffer.remaining()))
    override fun encode(buffer, value, ctx) {
        buffer.writeBytes(value.bytes)
    }
    override fun wireSize(value, ctx): WireSize = WireSize.Exact(value.bytes.size)
    override fun peekFrameSize(stream, baseOffset): PeekResult = PeekResult.NoFraming
}

@ProtocolMessage
data class Slice15aLengthPrefixedPayload(
    @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
)
```

Tests cover: wire format (`00 03 'a' 'b' 'c'`), round-trip, 1-byte and
2-byte length values (UShort BE has 65535 max — pick a smaller boundary
test like 256), strict-bound rejection (claim 5 bytes but body has 3),
and `peekFrameSize` walk on drip-fed bytes.

The `BinaryData` + `BinaryDataCodec` defined in this probe is the type
slices 15c/15d reuse (move it to a shared test-fixture location once
slice 15a is green — `buffer-codec-test/.../payload/BinaryData.kt`
maybe).

### Slice 15b — validator: ban raw-bytes/buffer types at @ProtocolMessage field sites

**New validator rule.** The codec processor's `analyzeField` (or an
earlier pass) rejects field types that are:

- `kotlin.ByteArray`
- `com.ditchoom.buffer.ReadBuffer`
- `com.ditchoom.buffer.WriteBuffer`
- `com.ditchoom.buffer.PlatformBuffer`

Diagnostic: *"Field `<X>` in `<MessageClass>` has type `<TypeName>`,
which cannot appear directly in a `@ProtocolMessage` data class.
Wrap the bytes in a typed value class implementing `Payload` and
reference its `Codec<T>` via `@UseCodec` so ownership semantics are
explicit at the codec boundary. See slice 15 handoff D1/D2."*

Probe: a `buffer-codec-processor:test` test case that runs KSP against
a fixture declaring `@ProtocolMessage data class X(val data: ByteArray)`
and asserts the validator rejects with the diagnostic above. Add a
parallel test for `ReadBuffer` and one for the value-class-wrapping-
`ByteArray` accepted shape.

The rule applies recursively only at the top-level field site —
fields whose **declared type** is one of the banned types. The user
can have `ByteArray` *inside* a value class they define (e.g.,
`BinaryData.bytes: ByteArray`); the validator only sees the declared
field type at the `@ProtocolMessage` level, which is `BinaryData` (the
value class), not `ByteArray`.

### Slice 15c — `CorrelationData` + `AuthenticationData` property variants

Two new sealed-variants of `MqttV5Property` (relocated `BinaryData` +
`BinaryDataCodec` from slice 15a's probe to the shared payload package
already lands in this slice if you want, or in a tiny separate slice
between 15a and 15c — author's call):

```kotlin
@PacketType(value = 0x09)
data class CorrelationData(
    @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
) : MqttV5Property

@PacketType(value = 0x16)
data class AuthenticationData(
    @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
) : MqttV5Property
```

Wire ids 0x09 and 0x16 are already documented in `MqttV5Property.kt`
kdoc as deferred (lines ~75-76). Test delta: extend
`MqttV5PropertyBreadthCodecTest` to cover both new variants.

### Slice 15d — `Connect.willPayload` + `Connect.password` retyped

Mechanical fixture-side change:

- v3 `MqttPacket.Connect.willMessage: String? = null` →
  `willPayload: BinaryData? = null` with `@LengthPrefixed
  @UseCodec(BinaryDataCodec::class) @When("connectFlags.willPresent")`.
- Same for `Connect.password`, gated on `connectFlags.passwordPresent`.
- Same for v5 `MqttV5Packet.Connect`.

Test sites update: every fixture-construction call site that passed
`willMessage = "lastwords"` becomes `willPayload =
BinaryData("lastwords".encodeToByteArray())`, etc. Wire bytes don't
change (still UShort BE prefix + body) so wire-byte assertions stay.

### Slice 15e — cross-property uniqueness invariant (OPTIONAL)

Spec: most properties are "Protocol Error to include more than once"
except `UserProperty` + `SubscriptionIdentifier`. Two implementation
shapes:

- **`V5PropertyBag` wrapper.** New value class wrapping
  `List<MqttV5Property>` with init-block dedup. Every variant carrying
  `properties` switches type. Cleaner contract.
- **Per-list-site init-block.** Add `init { require(...) }` to every
  variant carrying a property list (~6 variants). Less invasive but
  duplicated.

**Recommend deferring** unless a test or wire-format vector forces it.
Most MQTT brokers/clients tolerate duplicate properties by taking the
first occurrence; the impossible-state risk is low compared to the
diff size.

## What this handoff is NOT

- Not authorization to skip the audit-2f sweep before slice 15. The
  10 FIX-TODAY findings are pre-requisites — landing them first means
  slice 15 inherits a clean baseline.
- Not a green light to allow `ByteArray` at field sites "just for the
  test fixture." D1 is firm. The validator rejection (slice 15b) is
  what makes D1 enforced rather than convention.
- Not authorization to skip slice 15a's probe and start with v5. Same
  doctrine 14c-prep enforced: a generic-emitter capability ships with
  a focused fixture before any production-shaped fixture exercises
  it.
- Not a redesign of `Payload`'s semantics. The `Payload` marker stays
  what it is (slice 10b/10d's typed-payload escape hatch); slice 15
  just extends the `@LengthPrefixed @UseCodec` shape to cover scalar
  Payload-typed fields in addition to the existing list shape.

## Verification (each slice in the roadmap)

```bash
./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck :buffer-flow:ktlintCheck
./gradlew :buffer-codec-test:compileKotlinLinuxX64 \
          :buffer-codec-test:compileKotlinJs \
          :buffer-codec-test:compileKotlinWasmJs
```

Test count after each slice (rough estimate):

| Slice    | jvmTest | processor:test | flow:jvmTest |
|----------|---------|----------------|--------------|
| start    | 490     | 68             | 36           |
| audit-2f | 500ish  | 68             | 36           |
| 15a      | 506ish  | 68             | 36           |
| 15b      | 506ish  | 70-72          | 36           |
| 15c      | 510ish  | 70-72          | 36           |
| 15d      | 510ish  | 70-72          | 36           |
| 15e      | 512ish  | 70-72          | 36           |

(Wire bytes don't change for round-trip-style tests so existing v3/v5
counts stay; the increment is from new probes + negative-construction
tests.)

## Prompt to start the next session

> **Resume Phase J.M.5 — implement audit-2f then slice 15a.**
>
> Read in order: `PHASE_J_M_5_SLICE_15_HANDOFF.md` top-to-bottom, the
> v5 fixtures (`MqttV5Packet.kt` + `MqttV5Property.kt`), and the
> slice-11 emitter sites (`buildLengthPrefixedUseCodecList` and
> friends in `CodecEmitter.kt`) to understand the existing
> `@LengthPrefixed @UseCodec` list shape — slice 15a is the scalar
> counterpart.
>
> Confirm green baseline before implementing:
>
> ```
> ./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
> ./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck :buffer-flow:ktlintCheck
> ```
>
> Expected `490 / 68 / 36`. ktlint clean.
>
> Implementation order is fixed: **audit-2f first, then 15a, then 15b,
> then 15c/d in either order, then optional 15e.** Each slice ships as
> a single commit unless its diff balloons enough that a subdivision
> aids review. Do NOT skip the slice 15a probe — the v5 fixtures must
> not be the first thing to exercise `@LengthPrefixed @UseCodec` for
> typed scalars.
>
> Verify after each slice: tests green per the table in §Verification,
> ktlint clean, cross-target compile clean.

## Open items not in scope for slice 15

- **Slice 16+ — `V5PublishFlags` typed companion** to
  `MqttFixedHeader.flags` for the PUBLISH variant (DUP/QoS/RETAIN
  bit-packed flags as a value class). Closes the
  NEEDS-EMITTER-WORK QoS=3 finding cleanly. Requires emitter
  ergonomics around exposing two value classes for one wire byte.
- **Slice 16+ — typed numeric range invariants via annotation.**
  Today's "value > 0u" findings land as init-block requires; a
  `@Range(min = 1u)`-style annotation would make this declarative.
- **Slice 16+ — protocol-version-split flags class.** v5 relaxed
  v3.1.1's username/password cross-bit rule on `MqttConnectFlags`.
  Today the shared value class hosts both. Splitting would tighten
  v3 invariants without over-constraining v5.

## What this handoff is NOT (continued)

- Not a list of slices to land all at once. Each slice ships
  independently with green tests + ktlint between them.
- Not authorization to skip writing negative-construction tests
  for the audit-2f invariants. The point of init-block requires is
  that they fire on bad input — uncovered invariants drift.
