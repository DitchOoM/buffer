# Phase J.M.5 — handoff after slice 14a (with a design pause)

**Read this top-to-bottom before writing code.** The previous session
shipped slice 14a in a way that solves the wrong abstraction; the next
session needs to do a design pass before continuing.

## Branch state

Branch HEAD `851332ca` on `feature/directional-codec`. Twelve commits
past the original audit baseline (`265e6fb9`):

| Commit | Subject | Test delta |
|--------|---------|------------|
| `06e48b1f` | audit-2a: dedupe `LengthPrefixedListSpec` composition | 0 |
| `a1f2ce17` | audit-2b: rename `elementIsSealed` → `elementIsBackPatch` | +2 |
| `77053046` | audit-2c: cascade-trailer nullability invariant | +4 |
| `9d92467b` | slice 10 (Tier A): property breadth + trailing properties | +40 |
| `1fe49692` | audit-2d: impossible-state guards on v5 fixtures | +15 |
| `1f305ee9` | slice 11a: emitter widening — sealed-parent inners | +5 |
| `64bab088` | ktlint cleanup: pre-existing `:buffer-codec-processor` violations | 0 |
| `7d6aa45f` | slice 11b: typed `V5XReasonCode` across cascading-trailer fields | -7 |
| `2f54e496` | slice 12: typed SubAck reason codes + `V5SubscriptionOptions` | +4 |
| `ac740fb5` | slice 13: VBI-bodied `SubscriptionIdentifier` (id 0x0B) | +4 |
| `806901f1` | audit-2e: id-byte invariant on `MqttV5Property` variants | +5 |
| `851332ca` | slice 14a: `@DerivedLength` MVP (fixed-size suffix) | +3 |

Read commit messages for rationale; don't re-litigate landed slices.

Test baseline at HEAD:

```
:buffer-codec-test:jvmTest    = 486
:buffer-codec-processor:test  = 63
:buffer-flow:jvmTest          = 36
```

ktlint clean across both `:buffer-codec-test` and
`:buffer-codec-processor`. Cross-target compile clean (linuxX64 / js /
wasmJs).

## The design tension (the reason this handoff exists)

Slice 14a shipped `@DerivedLength` — a field-level annotation that
throws `EncodeException` at encode time if the caller-supplied length
disagrees with the framework-computed value. Generated code:

```kotlin
val __lengthDerived: UInt = 3u
if (value.length != __lengthDerived) throw EncodeException(...)
MqttRemainingLengthCodec.encode(buffer, __lengthDerived, context)
buffer.writeUByte(value.payload)  // ... etc
```

This closes the **wire-level** impossible state: you can't encode bytes
that misframe the message. But it does **not** close the
**construction-level** impossible state — the data class still accepts
`PubAck(remainingLength = 99u, packetIdentifier = 1u)` and you only
find out at encode time. The memory representation is still wrong; we
just refuse to serialize it.

The honest structural fix is to **drop the length field from the data
class entirely.** Length prefixes are a wire-format concern, not a
domain concern. The codec should own them internally. The user-facing
shape becomes:

```kotlin
@ProtocolMessage
@FramedBy(MqttRemainingLengthCodec::class)  // or whatever name
data class PubAck(
    val header: MqttFixedHeader = MqttFixedHeader(0x40u),
    val packetIdentifier: UShort,
)
```

The codec internally:
- **Decodes** the length prefix, narrows `buffer.limit()`, decodes
  remaining fields, restores limit. Length is never propagated to the
  data class.
- **Encodes** the body fields, computes byte count, writes the prefix
  before/around the body bytes. Caller has no length to supply.

**Why this is the right fix:**

- Construction-level impossible state is impossible — there is no
  field to mismatch.
- API surface is *smaller*, not larger. Today's user has to know to
  pass `remainingLength = 2u`. With class-level framing, they don't
  know remaining-length exists.
- `equals`, `toString`, `copy`, `hashCode` no longer carry the
  framing field. Two `PubAck`s with the same packetIdentifier are
  equal even if the wire encoding wasted bytes (e.g., overlong VBI).
- Length-prefix framing isn't structurally different from sealed
  dispatch (also a wire concern handled internally by the codec) or
  fixed-header bytes (handled by `@PacketType`). It belongs at the
  class level.

**What slice 14a should become:** deprecated and removed once
class-level framing lands. Slice 14a's emit path (compile-time
constant, direct write, zero-copy) is the *correct mechanism*; the
wrong choice was making it a field-level annotation. The mechanism
ports to class-level cleanly.

## Zero-copy reality check

Asked and answered: slice 14a is zero-copy (single direct-write pass,
no scratch). The wider codec system is also zero-copy: existing
`LengthPrefixedString` uses **back-patching** (reserve a slot, write
the body, seek back to fill the slot) on the same buffer; same for
`LengthPrefixedUseCodecList`. `LengthPrefixedMessage` pre-computes
length via `wireSize as Exact` and writes in order. No scratch, no
copy.

**The `@DerivedLength` slice 14b sketch in the previous handoff WAS
NOT zero-copy.** The "scratch buffer + measure + emit prefix + copy"
pattern is exactly the copy this codebase is built to avoid.

For VBI-prefixed bodies with BackPatch suffix, three zero-copy options
exist:

1. **Reserve max prefix width (4 bytes for VBI), encode body, write
   actual prefix, shift body if prefix is narrower.** The shift is a
   small in-buffer move, not a separate scratch — but it is a copy of
   ≤3 bytes of body data.
2. **Always encode max-width prefix (overlong VBI permitted by spec
   §1.5.5).** No shift, no copy. Wastes ≤3 bytes per packet on
   short bodies. MQTT spec accepts this; many implementations do it.
3. **Two-pass over the body**: first pass via `wireSize as Exact`
   chains (BackPatch fields produce BackPatch) — but this only works
   when the body's *wireSize* can be computed without writing, which
   is precisely what BackPatch means it can't be. So this option
   reduces to "no BackPatch in body" which is slice 14a's
   constraint.

(2) is the cleanest zero-copy answer and matches existing conventions
in MQTT clients. Worth considering whether the framework should always
emit max-width VBI for derived prefixes.

## What the next session needs to do

**Don't write code yet.** Do a design pass and propose a plan. The
prompt below is calibrated for that.

### Open design questions

1. **Annotation shape and target.** Class-level
   `@FramedBy(C::class)` on a `@ProtocolMessage` data class? Or a
   different name (`@LengthPrefixed` is taken)? Single annotation or
   multiple (e.g., `@FramedBy` + `@FramedAfter("siblingField")` to
   place the prefix after a fixed header)?

2. **Where does the prefix sit on the wire?** v3/v5 packets have
   `header (1 byte) + remainingLength (VBI) + body`. The class-level
   annotation needs to express "prefix the body, but the body
   *starts* after the header field." Options:
   - Implicit: the annotation always means "prefix the entire
     message except the first FixedSize field that's the discriminator."
     Cute but fragile.
   - Explicit: `@FramedBy(C::class, after = "header")` or similar.
   - The annotation goes on a *region* — e.g., a marker like
     `@PrefixedBody fun body() = listOf(...)` — too clever.

3. **Composition with `@DispatchOn` sealed parents.** Today's MQTT
   v3/v5 dispatcher reads the header byte, dispatches by packet type,
   then calls the variant codec which expects to read the same header
   byte first. If `@FramedBy` goes on each variant, the variant codec
   reads `header + remainingLength` internally. The dispatcher's
   peek path needs the variant's wire width without invoking the
   variant — slice 14a's compile-time-constant suffix sum applies
   for case 1, but BackPatch variants are opaque to peek (the
   dispatcher already returns `NoFraming` for those).

4. **Decode API.** Currently `decode(buffer, context)` reads all
   fields, returns the data class. If the framing field isn't on the
   data class, the codec discards the length after applying bound.
   Straightforward — symmetric to how today's codec discards
   peek-only header bytes that aren't constructor params.

5. **Encode API for case 2 (BackPatch suffix).** Pick a zero-copy
   strategy: max-width prefix (option 2 above), or
   reserve-and-shift (option 1). Both work; option 2 is simpler and
   matches existing implementations; option 1 emits tighter wire on
   small bodies. Caller can't supply a length so neither has a
   correctness footgun.

6. **Migration / deprecation path.** Does `@DerivedLength` stay as a
   transitional path or get removed in the same slice as
   `@FramedBy` lands? My lean: remove. `@DerivedLength` is on the
   wrong abstraction; keeping it muddles the rule.

7. **What about non-bounding length prefixes?** The MVP scope:
   `BoundingLengthCodec<UInt>` (length narrows the buffer for
   subsequent fields). Plain `Codec<UInt>` lengths (e.g., a length
   that's just data, not a buffer-narrowing bound) are slice 13's
   `VariableByteIntegerCodec` shape — those don't need framing
   because they're field data, not framing. Keep `@FramedBy`
   restricted to bounding length codecs.

### What to keep from slice 14a

- The `@DerivedLength` validator's *suffix-must-be-fixed* logic ports
  cleanly to `@FramedBy` (case 1). The check itself is structurally
  the same: "are all fields of this class fixed-size."
- The compile-time-constant suffix sum in the encode emit ports
  cleanly: zero-copy, single pass.
- The probe fixture in `slice14a/Slice14aDerivedFrame` is a starting
  point for case 1's `@FramedBy` probe (would need rewriting to drop
  the length field from the data class, but the test shape stays
  similar).

The validator + emitter changes for `@DerivedLength` (in
`CodecEmitter.kt` analyzer + `appendEncodeUseCodecScalar` derived
branch + `validateDerivedLength` in `ProtocolMessageProcessor.kt`)
will be removed once `@FramedBy` subsumes them. Don't rebuild the
runtime-guard in `@FramedBy` — the structural fix doesn't need it.

## Prompt to start the next session

> **Resume Phase J.M.5 — design pass before slice 14b.**
>
> Read in order: this file (`PHASE_J_M_5_AUDIT_HANDOFF.md` from top),
> the slice 14a commit message (`git log 851332ca`), and the slice
> 11a commit message (`git log 1f305ee9`) for context on how
> capability slices have been landing. Then read `MqttPacket.kt`
> (v3 fixtures at
> `buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/mqtt/MqttPacket.kt`)
> and `MqttV5Packet.kt` to see what `remainingLength`-bearing data
> classes look like today.
>
> Confirm green baseline before designing:
>
> ```
> ./gradlew :buffer-codec-test:jvmTest :buffer-codec-processor:test :buffer-flow:jvmTest
> ./gradlew :buffer-codec-test:ktlintCheck :buffer-codec-processor:ktlintCheck
> ```
>
> Expected `486 / 63 / 36`. ktlint clean.
>
> **Design task** — propose a class-level length-prefix framing
> annotation (provisional name `@FramedBy`) that drops the length
> field from the primary constructor entirely. Address the seven
> open questions in this file's "Open design questions" section.
> The proposal should answer:
>
> 1. Annotation shape, target, parameters.
> 2. How the prefix's position on the wire is expressed when there's
>    a fixed header before it (v3/v5 case).
> 3. Composition with `@DispatchOn` sealed parents — does the
>    annotation go on each variant, on the parent, or both?
> 4. Encode strategy for BackPatch suffix (case 2): max-width VBI
>    vs. reserve-and-shift. Both must be zero-copy.
> 5. Migration path: deprecate-in-place vs. remove-with-replacement.
> 6. Probe fixture shape — case 1 first, case 2 follow-on, or both
>    in one slice.
> 7. Validator surface — what diagnostics does the user see for
>    common mistakes.
>
> **Don't write code yet.** Land a written design — comment, doc, or
> direct response — that I can react to. Specifically tell me which
> of the seven questions are *decisions you're making* vs. *open
> tensions you can't resolve without my input*. The previous session
> shipped 14a too fast because it didn't pause for this.
>
> Once the design lands and we agree, the implementation slices are:
>
> - **slice 14b**: `@FramedBy` capability + case 1 probe (fixed-size
>   suffix). Replaces 14a's emit path with class-level emit. Removes
>   `@DerivedLength` annotation + validator + emitter branch in the
>   same commit (slice 14a's mechanism survives, just at the right
>   abstraction).
> - **slice 14c**: case 2 (BackPatch suffix) via the chosen
>   zero-copy strategy.
> - **slice 14d** (if needed): substitute `@FramedBy` into v3/v5
>   fixtures, drop the `remainingLength` constructor parameter from
>   `MqttPacket.PubAck` etc. and `MqttV5Packet.*`. Test sites lose
>   one constructor argument per packet.

## Other open items (lower priority)

- **Slice 15+ — multi-payload dispatcher / aggregator.** CONNECT's
  binary will-payload + password (`@Payload WP`/`@Payload PP`).
  Substantial multi-slice phase. Not blocked on slice 14b/c/d. Closes
  the two property variants still deferred from slice 10 Tier A
  breadth (0x09 Correlation Data, 0x16 Authentication Data).

- **`PHASE_J_M_5_HANDOFF.md`** is the original kickoff prompt for
  Phase J.M.5 (predates everything in this file). Stale; can be
  deleted or archived. Untracked working-directory file like this
  one.

## What this handoff is NOT

- Not a list of slices to land. The next session's *first output* is
  a design proposal, not commits.
- Not a green light to redo slice 14a. Slice 14a stays committed —
  removing it only happens when `@FramedBy` lands as its replacement.
- Not authorization to attempt the structural fix without the design
  pass. The previous session's mistake was implementing before
  designing; don't repeat it.
