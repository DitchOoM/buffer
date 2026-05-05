# Phase J.M — Model the rest of MQTT v3.1.1

This is the design brief for Phase J.M, the next phase after Phase I.1
(`@RemainingLength` → `@UseCodec(BoundingLengthCodec)`). Read
`PHASE_I_REMAINING_LENGTH_PLUGGABLE.md` and `PHASE_I_1_RESUME.md` first
for the locked design Phase I.1 landed; this brief is the protocol-
modeling phase that uses the resulting machinery to build a complete
MQTT v3.1.1 fixture set on the sealed dispatcher.

## Goal

Produce a complete MQTT v3.1.1 wire model on the `MqttPacket<out P :
Payload>` sealed dispatcher — every CONTROL packet (types 1–14) as a
sealed variant — and delete the standalone `MqttConnect` /
`MqttSubAck` fixtures by folding their bodies into the matching
sealed variants.

After Phase J.M, the consumer-facing surface is a single generated
`MqttPacketCodec` that handles every v3.1.1 packet type:

```kotlin
val pkt: MqttPacket<TextPayload> = MqttPacketCodec.decode(buf, ctx)
when (pkt) {
    is MqttPacket.Connect    -> /* full §3.1 body */
    is MqttPacket.ConnAck    -> /* §3.2 */
    is MqttPacket.Publish    -> /* §3.3 with payload */
    is MqttPacket.PubAck     -> /* §3.4 */
    is MqttPacket.PubRec     -> /* §3.5 */
    is MqttPacket.PubRel     -> /* §3.6 */
    is MqttPacket.PubComp    -> /* §3.7 */
    is MqttPacket.Subscribe  -> /* §3.8 */
    is MqttPacket.SubAck     -> /* §3.9 (folded from MqttSubAck) */
    is MqttPacket.Unsubscribe-> /* §3.10 */
    is MqttPacket.UnsubAck   -> /* §3.11 */
    is MqttPacket.PingReq    -> /* §3.12 */
    is MqttPacket.PingResp   -> /* §3.13 */
    is MqttPacket.Disconnect -> /* §3.14 */
}
```

This unblocks Phase K (`mqtt` repo cutover to depend on the generated
codec instead of its hand-rolled implementation).

## Why this wasn't done in Phase I.1

Phase I.1 was about the **codec generator**. It proved the processor
can express every annotation shape MQTT needs — `@DispatchOn`
value-class discriminators, `@LengthPrefixed`, dotted `@WhenTrue`
predicates, `@RemainingBytes` payloads, `@UseCodec(BoundingLengthCodec)`,
generic `<P : Payload>` variants. The fixture redundancy in
`:buffer-codec-test` (simplified `Connect` on the sealed dispatcher
plus standalone `MqttConnect`; standalone `MqttSubAck` with no
sealed variant; missing 9 packet types entirely) is intentional:
each fixture isolates one capability so a regression in (say) the
dispatcher peek path can't be confused with a regression in the
var-int bound.

Phase J.M is the **protocol-modeling** phase. The mechanics are all
proven; J.M wires the proven mechanics into a complete v3.1.1 wire
model.

## Variant inventory

| Type | Wire byte¹ | v3.1.1 § | Current fixture state | J.M target |
|------|-----------|----------|-----------------------|------------|
| 1 | 0x10 | §3.1 CONNECT | `MqttPacket.Connect` (simplified) + standalone `MqttConnect` (full body) | Fold full body into `MqttPacket.Connect`; delete standalone. |
| 2 | 0x20 | §3.2 CONNACK | — | Add as new sealed variant. |
| 3 | 0x30 | §3.3 PUBLISH | `MqttPacket.Publish<P : Payload>` (complete; QoS=0 narrow, see "Open spec gaps"). | Keep; lift QoS-conditional `packetId` (see "Open spec gaps"). |
| 4 | 0x40 | §3.4 PUBACK | — | Add as new sealed variant. |
| 5 | 0x50 | §3.5 PUBREC | — | Add as new sealed variant. |
| 6 | 0x62 | §3.6 PUBREL | — | Add as new sealed variant; flag bits required. |
| 7 | 0x70 | §3.7 PUBCOMP | — | Add as new sealed variant. |
| 8 | 0x82 | §3.8 SUBSCRIBE | — | Add as new sealed variant; flag bits required; uses `@RemainingBytes List<TopicFilter>`. |
| 9 | 0x90 | §3.9 SUBACK | standalone `MqttSubAck` only | Fold body into `MqttPacket.SubAck`; delete standalone. |
| 10 | 0xA2 | §3.10 UNSUBSCRIBE | — | Add as new sealed variant; flag bits required; uses `@RemainingBytes List<String>`. |
| 11 | 0xB0 | §3.11 UNSUBACK | — | Add as new sealed variant. |
| 12 | 0xC0 | §3.12 PINGREQ | `MqttPacket.PingReq` (complete) | Keep. |
| 13 | 0xD0 | §3.13 PINGRESP | `MqttPacket.PingResp` (complete) | Keep. |
| 14 | 0xE0 | §3.14 DISCONNECT | `MqttPacket.Disconnect` (complete) | Keep. |

¹ "Wire byte" is the `MqttFixedHeader.raw` value the variant defaults
to — top 4 bits are the type discriminator (driven by
`@DispatchValue`), bottom 4 bits are flags. PUBREL / SUBSCRIBE /
UNSUBSCRIBE require the bottom-bit-2 flag set per spec; the variant's
`MqttFixedHeader` default initializer encodes this.

## Folding work

### `MqttSubAck` → `MqttPacket.SubAck`

The standalone fixture today (`MqttSubAck.kt:36-41`):

```kotlin
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttSubAck(
    val header: MqttFixedHeader,
    @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
    val packetIdentifier: UShort,
    @RemainingBytes val returnCodes: List<UByte>,
)
```

Becomes (in `MqttPacket.kt`):

```kotlin
@PacketType(value = 9)
@ProtocolMessage(wireOrder = Endianness.Big)
data class SubAck(
    val header: MqttFixedHeader = MqttFixedHeader(0x90u),
    @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
    val packetIdentifier: UShort,
    @RemainingBytes val returnCodes: List<UByte>,
) : MqttPacket<Nothing>
```

Mechanical changes: add `@PacketType(value = 9)`, add the
`MqttFixedHeader` default initializer (matches the spec wire byte
0x90), add `: MqttPacket<Nothing>`. The body shape is identical.

### `MqttConnect` → `MqttPacket.Connect`

Today the sealed `Connect` is **simplified** (`MqttPacket.kt:88-95`)
— it only exercises `keepAliveSeconds` + `clientId`. The standalone
`MqttConnect.kt:90-103` carries the full §3.1 body with the four
conditional payload fields. The fold replaces the simplified variant
with the full body:

```kotlin
@PacketType(value = 1)
@ProtocolMessage
data class Connect(
    val header: MqttFixedHeader = MqttFixedHeader(0x10u),
    @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val connectFlags: MqttConnectFlags,
    val keepAliveSeconds: UShort,
    @LengthPrefixed val clientId: String,
    @LengthPrefixed @WhenTrue("connectFlags.willPresent") val willTopic: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.willPresent") val willMessage: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.usernamePresent") val username: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.passwordPresent") val password: String? = null,
) : MqttPacket<Nothing>
```

Then delete `MqttConnect.kt` (the standalone fixture). `MqttConnectFlags`
moves to `MqttPacket.kt` or its own file.

### Files to delete

- `MqttSubAck.kt` (after fold).
- `MqttConnect.kt` (after fold) — but `MqttConnectFlags` value class
  must move first.
- `MqttCodec.kt` / `MqttConnectProtocolName.kt` / `MqttSubAckBody.kt` —
  audit whether these legacy slice-N doctrine fixtures are still
  referenced. If not, delete them too. If a downstream test still
  uses one as a slice-isolation regression net, leave it.

### Test migration

| Test file | Current target | J.M target |
|-----------|---------------|------------|
| `MqttSubAckCodecTest.kt` | `MqttSubAckCodec.{decode, encode, peekFrameSize}` | `MqttPacketCodec.SubAck.{decode, encode}` (per-variant codec object on the sealed dispatcher's emit shape) OR drive through `MqttPacketCodec.decode` and pattern-match the result |
| `MqttConnectCodecTest.kt` | `MqttConnectCodec.{...}` | Same as above |
| `MqttPacketCodecTest.kt` | Existing dispatcher tests | Add per-variant assertions for the 9 new variants; drop the simplified-Connect tests when the full Connect lands |

Two ways to migrate the standalone tests:

1. **Per-variant codec dispatch.** The slice 6 emit produces nested
   codec objects per variant (`MqttPacketCodec.SubAck`,
   `MqttPacketCodec.Connect`, etc.). Tests can reach the per-variant
   codec directly for round-trip assertions and use the dispatcher
   for end-to-end coverage. This preserves byte-exact assertions
   without forcing every test to materialize a full `MqttPacket<P>`.
2. **Sealed-root only.** All tests go through `MqttPacketCodec.decode`
   and pattern-match the sealed type. Cleaner consumer-shape, but
   noisier byte-exact tests.

Pick (1) for the migration — keeps test-shape close to the existing
standalone tests so the diff is mechanical.

## Open spec gaps

These are MQTT v3.1.1 wire-level details that the current fixtures
don't model. J.M should resolve them with explicit decisions per
gap.

### 1. `Publish.packetId` is QoS-conditional

The fixture today always includes `packetId`, but per §3.3.2.2 it's
present only when QoS > 0 (the QoS bits live in
`header.flags & 0x06`). The narrow doc comment (`MqttPacket.kt:113-117`)
flags this:

> Per spec, packetId is only present when `header.flags & 0x06 != 0`
> (QoS > 0); slice 10f narrow always includes it [...]. Lifting this
> to a QoS-conditional `@WhenTrue("header.flags > ...")` waits for a
> vector that exercises QoS-bit dotted predicates against the
> value-class header.

J.M is that vector. Two routes:

a. **Add a QoS-extracting property to `MqttFixedHeader`** that returns
   `Boolean` so the existing `@WhenTrue` shape works:

   ```kotlin
   value class MqttFixedHeader(val raw: UByte) {
       @DispatchValue val packetType: Int get() = raw.toUInt().shr(4).toInt()
       val flags: UByte get() = (raw.toUInt() and 0x0Fu).toUByte()
       val qosGreaterThanZero: Boolean get() = (raw.toUInt() and 0x06u) != 0u
   }
   ```

   Then `Publish` becomes:
   ```kotlin
   @WhenTrue("header.qosGreaterThanZero") val packetId: PacketId? = null,
   ```

   This is the minimum-scope path — uses only existing emitter
   capability.

b. **Lift `@WhenTrue` to support arithmetic predicates** (e.g.
   `"header.flags > 0u"` or `"(header.flags and 2) != 0"`). Larger
   scope, broader applicability beyond MQTT, but a substantial
   emitter capability gap.

Pick (a) for J.M.

### 2. v3.1.1 vs v5.0 wire format

This brief covers v3.1.1 only. v5.0 adds property lists to most
packets, which require step 11 of Phase I.1
(`@LengthPrefixed @UseCodec` composition for the property-list
shape). v5.0 modeling is **deferred to Phase J.M.5** or stays out of
scope until step 11 lands.

If the `mqtt` repo's cutover (Phase K) needs v5.0 immediately,
prioritize step 11 → J.M.5 in that order.

### 3. PUBACK/PUBREC/PUBREL/PUBCOMP/DISCONNECT v5's optional reason+props

Per `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md:326`, this is **Phase I.3**
(numeric-keyed conditional fields). v3.1.1's PUBACK et al. are
fixed-shape (`header + remainingLength=2 + packetIdentifier`), so
this gap is v5-only. Out of scope for J.M (v3.1.1).

### 4. CONNECT will-message and password are binary, not String

Per §3.1.3.3, the Will Message is "a sequence of zero or more
bytes" — arbitrary application-message bytes the broker republishes
verbatim. Per §3.1.3.5, the Password is "0 to 65535 bytes of binary
data". Neither is text; modeling them as `String` (as J.M step 4
does, inheriting from the standalone `MqttConnect` fixture's
slice-5b shape) is wire-correct only for ASCII fixtures because
UTF-8 happens to round-trip bytewise. It breaks the moment a
caller wants to send arbitrary bytes.

The proper shape is a `<P : Payload>`-typed slot:

```kotlin
data class Connect<WP : Payload, PP : Payload>(
    ...,
    @LengthPrefixed @WhenTrue("connectFlags.willPresent") val willMessage: WP? = null,
    @LengthPrefixed @WhenTrue("connectFlags.passwordPresent") val password: PP? = null,
) : MqttPacket<Nothing>
```

Two emitter capability gaps before this can land:

- **`@LengthPrefixed @UseCodec(C::class) val: P?`** — the property-
  list shape v5.0 needs, scoped as **Phase I.1 step 11**. Same
  emitter machinery the binary will-message / password fields need:
  a length-prefixed binary blob threaded through a caller-supplied
  `Codec<P>`.
- **Sealed-parent multi-generic dispatcher.** `MqttPacket<out P :
  Payload>` carries a single payload generic for `Publish<P>` today;
  `MqttPacketCodec(payloadCodec)` is a one-codec class. A
  `Connect<WP, PP>` variant means either widening the dispatcher
  to multi-generic or wiring per-call codec injection (the
  aggregator's pattern for `Publish`). The aggregator path is the
  cheaper of the two and consistent with how `Publish<P>` already
  defers payload codec selection to the call site.

Defer to **Phase J.M.5** (v5.0 modeling) — J.M.5 needs step 11
anyway for v5's property lists, and the binary lift composes
naturally with the v5 `Connect` body. Doing it now in J.M for
v3.1.1 only would land step 11 early, decoupling the J.M.0 →
J.M.5 sequencing the brief established. The escape hatch from
gap #2 applies: if the `mqtt` repo cutover (Phase K) needs binary
will-message / password before v5 modeling lands, prioritize step
11 → J.M.5 ahead of the rest of J.M.

Until then, the J.M step 4 fold's `String` modeling carries a
kdoc note ("technically arbitrary bytes per the spec; modeled as
String because the Stage E `@LengthPrefixed`-inner universe is
String only") so the gap doesn't get lost.

### 5. SUBSCRIBE / UNSUBSCRIBE topic-filter list shape

SUBSCRIBE per §3.8.3 carries a list of `(topicFilter: String, qos: UByte)`
pairs — a `@RemainingBytes List<TopicFilter>` where `TopicFilter` is a
`@ProtocolMessage data class(@LengthPrefixed val filter: String, val
qos: UByte)`. UNSUBSCRIBE per §3.10.3 is the same shape but qos-less
(`@RemainingBytes List<String>` of length-prefixed topics — but
`@RemainingBytes` today targets single-byte scalars or payload, not
`List<String>`).

Two emitter capability checks needed before J.M can land these:

- **`@RemainingBytes List<MessageType>`** — **resolved by J.M.0.**
  The slice 7c `RepeatedBlocks` doctrine fixture proves the
  capability end-to-end (encode + decode + wireSize-Exact + peek =
  NoFraming). SUBSCRIBE wraps a `MqttTopicFilter` data class
  (`@LengthPrefixed name: String, qos: UByte`) into the same shape.
- **`@RemainingBytes List<@LengthPrefixed String>`** — still open.
  UNSUBSCRIBE per §3.10.3 is a list of length-prefixed topics;
  the J.M.0 emitter accepts only `List<@ProtocolMessage data
  class>`, not bare strings. Solution: wrap with a single-field
  `data class MqttUnsubscribeTopic(@LengthPrefixed val name:
  String)` so UNSUBSCRIBE rides the J.M.0 path. No further emitter
  work required.

## Landing order

Each step is individually green; the test baseline must stay green
throughout.

1. **Audit `@RemainingBytes List<MessageType>` capability.** **LANDED
   as J.M.0** — the emitter slice and `RepeatedBlocks` doctrine
   fixture are in place. Per the audit:
   - Pre-J.M.0, `@RemainingBytes List<S>` accepted only single-byte
     scalars (`UByte` / `Byte`) via the slice 7b
     `RemainingBytesScalarList` shape; the doc on
     `analyzeRemainingBytesScalarListField` explicitly deferred
     `@ProtocolMessage` elements "until a vector requires them."
   - J.M.0 added `FieldSpec.RemainingBytesProtocolMessageList` plus
     analyze/decode/encode/wireSize/peek branches in
     `CodecEmitter.kt`. The decode/encode loops mirror
     `LengthFromList`'s element-codec dispatch (slice 7a) but bound
     by the caller-set buffer limit (mirror of slice 7b's
     `RemainingBytesScalarList`).
   - Doctrine fixture lives at
     `:buffer-codec-test/src/commonMain/.../slice7c/RepeatedBlocks.kt`
     with byte-exact tests at
     `:buffer-codec-test/src/commonTest/.../slice7c/RepeatedBlocksCodecTest.kt`
     (7 tests: empty + N-element encode, decode-until-buffer-limit,
     decode-respects-external-limit, round-trip, wireSize-Exact-via-
     element-sum, peekFrameSize-NoFraming).
   - `:buffer-codec-test:jvmTest` count: 232 → 239. Other module
     counts unchanged.

   This unblocks SUBSCRIBE / UNSUBSCRIBE (step 5). With J.M.0 landed,
   step 2 (`Publish.packetId` QoS-conditional) is the next entry
   point — independent of the rest and lands cleanly in parallel
   with steps 3–4 (the SubAck / Connect folds).
2. **Add `qosGreaterThanZero: Boolean` to `MqttFixedHeader`.** Update
   `Publish.packetId` to `@WhenTrue("header.qosGreaterThanZero")
   val packetId: PacketId? = null`. Run `MqttPacketCodecTest` —
   QoS=0 PUBLISH wire output drops 2 bytes (packetId absent); QoS>0
   adds the field. This is one annotation + one property addition;
   verify the existing PUBLISH tests cover both shapes (or add new
   tests).
3. **Fold `MqttSubAck` → `MqttPacket.SubAck`.** Add the variant to
   `MqttPacket.kt`. Migrate `MqttSubAckCodecTest` to drive
   `MqttPacketCodec.SubAck` (option 1 above). Delete `MqttSubAck.kt`.
   Run `:buffer-codec-test:jvmTest` — count drops by N (the
   `MqttSubAckCodec` references collapse) but new dispatcher tests
   add coverage.
4. **Fold `MqttConnect` → `MqttPacket.Connect`.** Move
   `MqttConnectFlags` to `MqttPacket.kt` (or `MqttConnectFlags.kt`).
   Replace the simplified `Connect` variant with the full body.
   Migrate `MqttConnectCodecTest`. Delete `MqttConnect.kt`.
5. **Add the 9 missing variants in dependency order:**
   - `PingReq` / `PingResp` / `Disconnect` are already done.
   - `PubAck` / `PubRec` / `PubRel` / `PubComp` / `UnsubAck` —
     2-byte body shape (header + RL + packetIdentifier). Fastest
     wins; one fixture file each, one test class each.
   - `ConnAck` — header + RL + 1-byte session-present + 1-byte
     return-code.
   - `Subscribe` / `Unsubscribe` — depend on step 1's
     `List<MessageType>` capability.
6. **Cleanup pass:** delete `MqttCodec.kt`,
   `MqttConnectProtocolName.kt`, `MqttSubAckBody.kt` if no test
   references them; collapse remaining slice-N-isolation fixtures
   that are now redundant with the full sealed family.
7. **End-to-end byte-exact regression suite:** add a
   `MqttFullPacketSetCodecTest` driving every variant through round-
   trip + peekFrameSize + drip-fed-stream tests.

## Acceptance

- `:buffer-codec-test:jvmTest` — green; count grows substantially
  (each new variant adds 5–10 tests). Target shape: every v3.1.1
  packet type covered by encode-byte-exact, decode-round-trip,
  peekFrameSize-Complete, peekFrameSize-NeedsMoreData-drip, and (for
  variants with conditional fields) WhenTrue branch coverage.
- `:buffer-codec-processor:test` — unchanged from Phase I.1's count
  unless step 1 (the `List<MessageType>` capability) lands; that
  adds validator/diagnostic tests.
- `:buffer-flow:jvmTest` — `aggregatorPathTopicKeyed` stays green;
  add a SUBACK-routed dispatcher acceptance test once SubAck is
  folded.
- `:buffer:jvmTest` — unchanged.
- Cross-target compile clean (JS/WasmJs/LinuxX64).
- ktlint clean on new fixtures.

## Out of scope for Phase J.M

- **MQTT v5.0** — needs Phase I.1 step 11 (`@LengthPrefixed
  @UseCodec` composition for the property-list shape). Defer to
  Phase J.M.5 (or sequence step 11 → J.M.5 if the `mqtt` repo
  cutover needs v5 immediately).
- **PUBACK et al. v5 reason+props** — needs Phase I.3 (numeric-keyed
  conditional fields).
- **`mqtt` repo cutover** — Phase K. Depends on J.M completion.
- **`websocket` repo modeling** — Phase J.W. Independent of J.M;
  needs `WebSocketLengthCodec` (BoundingLengthCodec<ULong>) as the
  Phase I.1 mirror exercise.
- **Hand-rolled MQTT codec deletion in `:buffer-codec-test`** — the
  legacy fixtures (`MqttCodec.kt`, etc.) get deleted in J.M's
  cleanup pass, but only if no test still references them.

## Risks and watchpoints

1. **Test-count drift.** Folding `MqttSubAck` / `MqttConnect`
   collapses N standalone tests; adding 9 new variants adds many
   more. Track the net delta — the briefing won't carry an exact
   target like Phase I.1 did, but order-of-magnitude growth is
   expected (current 232 → likely 350+).
2. **Flag-bit defaults vs caller-supplied.** Variants like PUBREL
   (header byte 0x62, bottom bit 2 mandatory) need careful
   defaulting. If a caller constructs `MqttPacket.PubRel(header =
   MqttFixedHeader(0x60u), ...)` the wire output is malformed per
   spec but the dispatcher still routes by top 4 bits. Decide:
   trust the caller (current sealed-variant doctrine), or add a
   constructor `init { require(header.flags == 0x02u.toUByte()) }`
   guard. Recommend: trust the caller; add a kdoc note.
3. **`MqttConnect.protocolLevel = 0x04u`.** v3.1.1 specifies this
   value; v5.0 uses 0x05. Hard-coding 0x04 in the sealed variant's
   default is fine for v3.1.1 only; v5 modeling will need a
   separate `MqttConnectV5` variant or an `@UseCodec`-driven
   protocol-level dispatcher.
4. **Slice-emit shape for nested codec objects.** Confirm the slice 6
   emit produces `MqttPacketCodec.SubAck` (member codec object on
   the sealed dispatcher) so per-variant tests can reach it. If
   instead it produces a top-level `MqttPacketSubAckCodec` or only a
   private `Codec<SubAck>` instance, adjust the test-migration
   strategy.
5. **`MqttFixedHeader` constructor injection.** Sealed variants today
   default the header's wire byte (`MqttFixedHeader(0x90u)` for
   SubAck etc.). The dispatcher PEEKS the header byte first, then
   the variant codec re-reads the same byte. Confirm the existing
   peek + double-read behavior holds for the new variants — slice 6
   doctrine handles this for `Publish` / `PingReq` etc., should
   extend mechanically.

## How to start the J.M session

1. Read `PHASE_I_REMAINING_LENGTH_PLUGGABLE.md` and
   `PHASE_I_1_RESUME.md` for the Phase I.1 design baseline.
2. Read this file (`PHASE_J_M_BRIEF.md`).
3. Verify branch HEAD includes `Phase I.1 step 10: delete
   @RemainingLength`. Confirm green baseline at 232/53/36/1008
   across the four test suites.
4. Start with step 1 — audit `@RemainingBytes List<MessageType>`.
   The audit answer determines whether J.M can proceed straight to
   step 2 or needs a J.M.0 emitter slice first.
5. Step 2 (`Publish.packetId` QoS-conditional) is independent of
   step 1 and can land in parallel.
6. Steps 3–5 follow the dependency chain: SubAck/Connect folds (steps
   3–4) before adding new variants (step 5), so the new variants
   land in a complete sealed family.
7. Step 7's end-to-end suite goes last — it's the regression net for
   the full v3.1.1 model.
