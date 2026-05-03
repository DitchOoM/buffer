# Stage D — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the Stage C closeout session
> (resolved 2026-05-03) to the Stage D planning + implementation
> session. Once Stage D's first slice lands, delete this file in the
> same commit.

Resuming on the buffer repo at `/home/rbehera/git/buffer`, branch
`feature/directional-codec`, on top of three **local-only** Stage C
commits at the tip:

- `d723173` — Stage C slice 3: JFR allocation tracker enforces
  zero-`[B` contract on JVM
- `6475a40` — Stage C slice 2: emit `@LengthPrefixed val: String` +
  signed scalar widening
- `3775b3b` — Stage C slice 1: lock `LengthPrefix` shape,
  BackPatch-for-String, JFR allocation tracker

Plus the Stage A + B tips (`fc426a2`, `7592a14`) below them. None of
the 94 local commits has been pushed (per the standing "do not push
without confirmation" carryover).

## TL;DR

Stages A, B, and C are closed. The KSP emitter generates working
codecs for fixed-size scalars (signed and unsigned), `@WireBytes`
narrowing, `@WireOrder` overrides, top-level `@JvmInline value class`
wrappers, `@LengthPrefixed @ProtocolMessage`-typed terminal fields
(slice-3 setLimit + restore), and `@LengthPrefixed val: String`
terminal fields with `WireSize.BackPatch`. The validator enforces §8
raw-bytes ban, R1 (adjacent `@LengthFrom`), R3 (`@LengthPrefixed` on
`@ProtocolMessage` data class fields, now widened to accept
`String`), and R4 (`@WireBytes` width range). JVM hot path is
allocation-clean for `[B` per Locked Decision row 16. Test tally: 60
cases green, 0 failures.

Stage D is the next capability gate. **Do not write code yet.**
Survey scope, lock the open deferred decisions for Stage D (none of
the active deferrals are scheduled for D — the gates are emitter
shape, not doctrine), and propose a test-driven sequence — Stage C's
`STAGE_C_RESUME.md` was the model.

## What's already locked

`PHASE_9_RESET.md` Locked Decisions rows 1–16 are durable. The
load-bearing ones for Stage D:

- **Row 1** — `Codec<T>` interface union. The dispatcher emitted by
  Stage D returns the sealed parent type from `decode`, takes the
  sealed parent in `encode`, and the variant codecs each implement
  `Codec<Variant>`. The dispatcher also implements `Codec<Parent>`.
- **Row 2** — `WireSize`: `Exact(bytes)` fast path, `BackPatch`
  default. The dispatcher's `wireSize` adds the discriminator byte
  to the variant's wireSize. If any variant returns `BackPatch` (e.g.,
  Stage D's `Echo` variant has a `@LengthPrefixed val msg: String`),
  the dispatcher returns `BackPatch`; if all variants are `Exact`,
  the dispatcher returns `Exact(1 + variantBytes)`.
- **Row 3** — `PeekResult`: `Complete(bytes)`, `NeedsMoreData`,
  `NoFraming`. Stage D's dispatcher peeks the discriminator byte then
  delegates to the matched variant's `peekFrameSize` with `baseOffset
  + 1`. The total returned to the caller is `1 + variantTotal`.
- **Row 11** — Sibling top-level `object MyMessageCodec`. Same shape
  for the dispatcher.
- **Row 12** — Emit `peekFrameSize` whenever statically determinable.
  For Stage D's `Command` shape: peek discriminator (1 byte minimum),
  match variant, delegate. If a variant lacks `peekFrameSize` or
  returns `NoFraming`, the dispatcher returns `NoFraming`.

## What Stage D must lock

**Nothing on the deferred-decisions table is scheduled for Stage D.**
Reading `PHASE_9_RESET.md`'s Stages plan:

- Stage E owns `@LengthFrom` resolution, `@WhenTrue` DSL, field-path
  tracking.
- Stage F owns `data object` vs empty `data class` for body-less
  variants.
- Stage H owns `@UseCodec` `expect`/`actual` resolution.

Stage D's only doctrine-shaping question is **error handling on
unknown discriminator**. The annotation kdoc says "duplicate
`@PacketType` values are compile errors" (already locked per Stage B
guard work). But: what does the generated dispatcher do when decode
sees a discriminator byte that matches no variant? Options:

1. Throw a typed `DecodeException` with `expected = "one of {0x01,
   0x02}"` and `actual = "0xFF"` — fail-loud, no escape hatch.
2. Throw via a default `else -> throw …` lambda the consumer can
   override at call site (Stage H pattern for `@Payload`).
3. Return null / use a sentinel — out of character with the rest of
   the codec contract (Codec.decode returns T, not T?).

Locked-leaning answer: option 1 for Stage D. The default-lambda
pattern in option 2 is Stage H's `@Payload` machinery; Stage D's
sealed-parent dispatch doesn't need it because the variant set is
closed by the sealed hierarchy. Adding it now would be premature
abstraction. Promote this to Locked Decisions row 17 in slice D1.

## Stage D scope (from `PHASE_9_RESET.md`)

> ### Stage D — Simple sealed dispatch with `@PacketType`
>
> - **Vector:** `sealed interface Command { @PacketType(0x01) data class
>   Ping(val ts: Long); @PacketType(0x02) data class Echo(@LengthPrefixed
>   val msg: String) }`.
> - **Capability:** 1-byte discriminator read/write; exhaustive `when` in
>   the generated dispatcher; `peekFrameSize` peeks discriminator + the
>   variant's frame.
> - **Acceptance:** both variants round-trip byte-exact; duplicate
>   `@PacketType` is a compile error; `peekFrameSize` correct across
>   variants.

A real-spec fixture for Stage D's shape (single-byte discriminator +
fixed and variable variants):

- **WebSocket close codes** — small enum of close reasons plus an
  optional reason string. Real spec, simple, fits the variant shape
  cleanly. Could be the real-spec sibling fixture if Stage D wants
  cross-coverage beyond the doctrine `Command` vector.
- **MQTT v3 PINGREQ / PINGRESP / DISCONNECT** — fixed packet types
  with no body. Each is a 2-byte fixed-header frame (type + flags +
  remaining-length=0). But MQTT uses `@DispatchOn` (Stage F) because
  the discriminator is the high nibble of a packet byte — *not*
  `@PacketType`. Skip until Stage F.
- **STOMP frames** — text protocol, doesn't fit binary discriminator.
- **DNS opcode field** — 4-bit field inside a header byte; would need
  bit unpacking. Skip until Stage F.

Surface a candidate during the first planning slice — `Command` plus
WebSocket close-codes is the cleanest pairing. The doctrine `Command`
vector exercises the variable terminal (Echo's String body); the
WebSocket fixture exercises the fixed-only-discriminator path
(PingReq-like, body bytes are minimal or empty).

## Suggested test-driven sequence

(Don't take this as final — propose your own after surveying.)

1. **Lock the unknown-discriminator behaviour.** Single design commit:
   add Locked Decisions row 17 to `PHASE_9_RESET.md` (throw
   `DecodeException` with field-path including the sealed parent
   simple name and the unmatched byte value). No emitter changes.
2. **Smallest emitter slice — sealed parent dispatcher.** Target the
   doctrine `Command` vector. Emitter analyzes `@ProtocolMessage
   sealed interface` symbols, validates that every direct subclass
   carries `@PacketType` with a unique `value`, generates one
   dispatcher object that:
   - `decode`: reads 1-byte discriminator, `when` over the variant
     codecs, returns the parent type
   - `encode`: `when (value)` writes the discriminator byte then
     delegates to the matched variant codec
   - `wireSize`: 1 + variant.wireSize (Exact if all variants are Exact,
     else BackPatch)
   - `peekFrameSize`: peek discriminator at baseOffset, dispatch to
     variant's `peekFrameSize(stream, baseOffset + 1)`, return Complete
     of `1 + variantTotal` or NeedsMoreData / NoFraming as appropriate
3. **Round-trip + dispatch tests** — Ping (8-byte ts) and Echo (variable
   String body). Wire layout:
   - `Ping(ts=0x1122_3344_5566_7788)` → `01 11 22 33 44 55 66 77 88`
   - `Echo(msg="hi")` → `02 00 02 68 69`
   Plus: peekFrameSize drip-feed for both, unknown discriminator throws
   `DecodeException` with the unmatched byte in `actual`.
4. **Compile-error tests** — duplicate `@PacketType(0x01)` across two
   variants is a KSP error; missing `@PacketType` on a variant is a
   KSP error; `@PacketType` on a non-sealed-variant class is silently
   skipped (consistent with Stage A/B/C: out-of-shape symbols don't
   emit, validators surface the actual diagnostic).
5. **Real-spec fixture** — WebSocket close-code enum encoded as a
   sealed parent with `@PacketType` per close reason. Confirms the
   dispatcher composes with single-field variants.
6. **Allocation tracker extension** — point the JFR test at the
   dispatcher (encode + decode of the doctrine `Command` vector) to
   confirm sealed dispatch doesn't introduce per-call `[B`
   allocations. Reuse `JfrAllocationTracker` from Stage C; add a
   `CommandAllocationTest`.
7. **Full check** — `:buffer-codec-processor:test
   :buffer-codec-test:jvmTest` green; new fixture tests pass; existing
   60 cases regression-free.

Open questions to address while sequencing:

- The dispatcher's `wireSize` needs an inner-codec call to know if the
  variant is Exact or BackPatch. For `Exact + 1` vs `BackPatch`, that's
  a per-variant runtime check — emitter can either produce `when (value)
  { ... -> Exact(1 + N); ... -> BackPatch }` or always return
  `BackPatch` if any variant could be variable. Pick the tighter option
  (per-variant when) — it lets Ping report `Exact(9)` even when sharing
  a parent with the variable-length Echo.
- Discriminator width is fixed at 1 byte for `@PacketType`. No
  `@PacketType` width override exists today; Stage D doesn't need one.
  Wider discriminators are Stage F's `@DispatchOn` shape (value class
  carrying a multi-byte raw + `@DispatchValue` getter).
- `@PacketType(value=0x01, wire=...)` — the `wire` parameter is for
  Stage F's `@DispatchOn` use case. For simple `@PacketType` dispatch,
  emit the `value` byte directly and ignore `wire` (or assert
  `wire == -1` or `wire == value` if set). Validator can check this.

## Carryovers (still in force)

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- mavenLocal republish stays deferred until after Stage H.
- Apple build verification carryover from commits `f0a68a9` /
  `318b638` (touched `MutableDataBuffer` paths) — confirm on a macOS
  host before any merge to main. Not blocking Stage D.
- Eight `*_BUG.md` / `*_ISSUES.md` notes at repo root remain out of
  scope. Don't sweep them.
- Pre-existing ktlint violations in
  `buffer-codec-processor/src/main/.../ProtocolMessageProcessor.kt`
  (Stage B), `DnsHeaderCodecTest.kt`, and `FlvTagHeaderCodecTest.kt`
  (Stage B) are tech debt, not Stage D blockers. Don't sweep unless
  asked.
- §8 / R1 / R3 / R4 validator rules stay. Stage D adds the sealed-
  dispatch validators alongside, not in place of them.
- WASM/`nonJvm` `writeString` allocates one internal `byte[]` per
  call — Locked Decision row 16 documents this as a runtime-side
  follow-up, not a codec-emitter regression. Stage D inherits the
  same JVM-only zero-`[B` claim.
- **Stage-H follow-up** (deferred bundle: MQTT `CorrelationData` /
  `AuthenticationData` migration to 2-field shape, R1 Payload
  exclusion removal, R3 widening to `@Payload` type parameters,
  `mqttPropertySize` deletion) is its own work. Stage D should *not*
  preempt Stage H.

## Read these to load context

- `buffer/CLAUDE.md` — `BufferFactory` discipline, wrapper
  transparency, codec-using-protocol-codecs guidance, sealed dispatch
  patterns, `peekFrameSize` contract, `CodecContext` semantics.
- `PHASE_9_RESET.md` — Locked Decisions rows 1–16, Stages A–H plan,
  Deferred Decisions table.
- `PHASE_10_DESIGN_NOTES.md` — derivation history; for Stage D look
  for the `@PacketType` / sealed dispatch sections.
- `buffer-codec-test/.../simple/SimpleHeader.kt` and
  `.../mqtt/MqttConnectProtocolName.kt` — Stage C reference fixtures.
- `buffer-codec-test/.../riff/{RiffChunkHeader.kt, WavFmtChunk.kt}`
  — Stage A reference. WavFmtChunk's `setLimit + restore` decode is
  the closest existing analogue for Stage D's "delegate to inner
  codec" pattern.
- `buffer-codec/.../Annotations.kt` — annotation surface; Stage D
  exercises `@PacketType` on variants of a `@ProtocolMessage sealed
  interface`. The kdoc already documents the Stage D shape.
- `buffer-codec-processor/.../CodecEmitter.kt` — the Stages A + B + C
  emitter. Stage D adds a sealed-parent branch in `analyze` and a new
  emission path for the dispatcher object.
- `buffer-codec-processor/.../ProtocolMessageProcessor.kt` —
  validators (§8, R1, R3 implicit, R4). Stage D adds: (a) every
  variant of a `@ProtocolMessage` sealed parent must carry
  `@PacketType`; (b) `@PacketType.value` must be unique per parent;
  (c) `@PacketType` on a non-variant class is reported.
- `buffer-codec-test/src/jvmTest/.../alloc/JfrAllocationTracker.kt`
  — the allocation tracker from Stage C slice 3. Reuse for Stage D's
  zero-`[B` regression test.
- `buffer/src/.../ReadBuffer.kt` and `.../WriteBuffer.kt` — `readByte
  / writeByte` for the discriminator byte. No new runtime APIs needed
  for Stage D.

## Five most recent commits (local)

```
d723173 Stage C slice 3: JFR allocation tracker enforces zero-byte[] contract on JVM
6475a40 Stage C slice 2: emit @LengthPrefixed val: String + signed scalar widening
3775b3b Stage C slice 1: lock LengthPrefix shape, BackPatch-for-String, JFR allocation tracker
7592a14 Stage B: emit @WireBytes-narrowed scalars + value-class top-level codecs
fc426a2 Stage A: emit RIFF/WAV codecs from KSP, retire hand-written references
```

## When this file's job is done

Delete `STAGE_D_RESUME.md` in the same commit that closes the first
Stage D slice (whichever fixture is emitted first). The locked
decision absorbed in Stage D (row 17 — unknown-discriminator
behaviour) gets added to `PHASE_9_RESET.md` as a new Locked Decisions
row; this ephemeral hand-off has no further role.
