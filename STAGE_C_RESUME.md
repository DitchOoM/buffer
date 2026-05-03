# Stage C — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the Stages A + B closeout session
> (resolved 2026-05-03) to the Stage C planning + implementation
> session. Once Stage C's first slice lands, delete this file in the
> same commit.

Resuming on the buffer repo at `/home/rbehera/git/buffer`, branch
`feature/directional-codec`, on top of two **local-only** commits at
the tip:

- `7592a14` — Stage B: emit @WireBytes-narrowed scalars + value-class
  top-level codecs
- `fc426a2` — Stage A: emit RIFF/WAV codecs from KSP, retire hand-
  written references

Neither has been pushed to origin yet (per the standing "do not push
without confirmation" carryover). `git log feature/directional-codec`
shows them at the tip.

## TL;DR

Stages A and B are closed. The KSP emitter generates working codecs
for fixed-size unsigned-scalar `data class` shapes (with optional
`@WireOrder` per-field overrides), `@LengthPrefixed` on a terminal
`@ProtocolMessage`-typed field (with `setLimit` + restore decode and
prefix-peek `peekFrameSize` overflow guards), `@WireBytes(N)`
narrowing on unsigned scalars (with encode-side `EncodeException`
overflow guards), and `@JvmInline value class` top-level wrappers
around a single inner unsigned scalar. The validator enforces §8 raw-
bytes ban, R1 (adjacent `@LengthFrom`), R3 (`@LengthPrefixed` on
`@ProtocolMessage` data class fields), and R4 (`@WireBytes` width
range). Test tally: 45 cases green, 0 failures.

Stage C is the next capability gate. **Do not write code yet.** Start
by surveying scope, locking the open deferred decisions, and proposing
a test-driven sequence — Stage A's `STAGE_A_RESUME.md` briefing was
the model.

## What's already locked

`PHASE_9_RESET.md` Locked Decisions rows 1–13 are durable. The
load-bearing ones for Stage C:

- **Row 1** — `Codec<T>` interface union (`Encoder` / `Decoder` /
  `SuspendingDecoder` / `FrameDetector`). Emitted codecs implement
  `Codec<T>`.
- **Row 2** — `WireSize` sealed: `Exact(bytes)` fast path,
  `BackPatch` default. Stage C is the first stage that actually
  produces `BackPatch`.
- **Row 3** — `PeekResult` sealed: `Complete(bytes)`,
  `NeedsMoreData`, `NoFraming` default. Variable-length codecs
  generally cannot peek a precise frame size from a string body
  alone; `peekFrameSize` for slice-with-`@LengthPrefixed`-string
  still works because the prefix is the whole frame size.
- **Row 5** — Sync = `buffer.setLimit()`, async = `parent.slice(N).use { }`;
  encode stays sync. The slice-3 `@LengthPrefixed @ProtocolMessage`
  shape uses `setLimit + try/finally restore`. Stage C reuses that
  pattern for `@LengthPrefixed val name: String` — the body is a
  UTF-8 byte stream rather than an inner codec call.
- **Row 10** — KotlinPoet via KSP common metadata. Generated codecs
  land in `commonMain` so all targets (jvm, js, wasm, native) see
  the same symbols.
- **Row 11** — Sibling top-level `object MyMessageCodec`.
- **Row 12** — Emit `peekFrameSize` whenever statically determinable.

## What Stage C must lock

Two deferred decisions from `PHASE_9_RESET.md` resolve in Stage C
before code can land:

- **`LengthPrefix` enum shape.** Today the enum is `Byte` / `Short` /
  `Int` (Stage A slice 3 used `Int` for WAV). Stage C is the
  first stage where the prefix bytes carry a String's UTF-8 length —
  a value that can be smaller than 256 (`Byte` prefix), under 64 KiB
  (`Short`), or up to 2 GiB (`Int`). Decide whether `Varint` or a
  parameterized `Fixed(N)` / `Variable` shape is needed now or can
  stay deferred. Current shape covers most fixed-prefix protocols;
  MQTT-style varint length is its own topic that lands when the
  protocol forces it (Stage E or later).
- **Zero-`ByteArray` enforcement mechanism.** The Phase 9 reset's
  item 3 says no `ByteArray` in production code paths or generated
  code. The String encode/decode path is the first place this is
  load-bearing — a naive emitter would write
  `value.field.encodeToByteArray()` then `writeBytes(...)`. Pick a
  mechanism: an allocation tracker hooked into
  `:buffer-codec-test:jvmTest`, manual bytecode inspection on the
  generated codec, or `buffer.writeString(text, Charset.UTF8)`
  semantics that the runtime guarantees stay zero-`ByteArray`. The
  decode path equivalently must avoid materializing a `ByteArray`
  intermediate — `buffer.readString(length)` already exists; verify
  it has a zero-`ByteArray` guarantee on each platform.

Other deferred decisions (`@LengthFrom` resolution form, `@WhenTrue`
DSL, `@UseCodec` `expect`/`actual`, field-path tracking) belong to
later stages (E, H) and should *not* be reopened in Stage C unless
directly blocking.

## Stage C scope (from `PHASE_9_RESET.md`)

> ### Stage C — Length-prefixed variable terminal field
>
> - **Vector:** `data class SimpleHeader(val id: Int, @LengthPrefixed val
>   name: String)` — fixed prefix + UTF-8 body.
> - **Capability:** `BackPatch` path. Encode uses `GrowableWriteBuffer`
>   and patches the length prefix after the body is written; decode reads
>   `len`, then exactly `len` UTF-8 bytes; codec defaults to
>   `WireSize.BackPatch`.
> - **Acceptance:** round-trip across empty / ASCII / multi-byte UTF-8;
>   allocation tracker confirms zero `ByteArray` allocations on the hot
>   path.

A real-spec fixture — pick one with a `@LengthPrefixed val: String`
shape that doesn't drag in other unrelated capabilities:

- **MQTT v3 CONNECT protocol-name field** — fixed shape `0x00 0x04
  'M' 'Q' 'T' 'T'` (a 2-byte BE length prefix + UTF-8 body). Real
  spec, simple. Could be modeled as a fixture even before Stage E
  lands `@LengthFrom` etc.
- **PostgreSQL StartupMessage / parameter list** — text fields with
  null-terminated bytes. Doesn't fit `@LengthPrefixed`.
- **WebSocket extension/protocol header strings** — small
  `@LengthPrefixed` strings inside the WebSocket handshake variants.
- **DNS QNAME** — sequence of length-prefixed labels (`Byte` prefix,
  zero-terminated). Multiple length-prefixed bytes per label, but
  the per-label part fits Stage C's shape.

Surface a candidate during the first planning slice — the MQTT
protocol-name string is the cleanest single-fixture choice.

## Suggested test-driven sequence

(Don't take this as final — propose your own after surveying. The
ordering below is a starting point.)

1. **Lock the `LengthPrefix` enum shape and the zero-`ByteArray`
   mechanism.** Single design commit, no code emission yet,
   doctrine rows added to `PHASE_9_RESET.md` if a decision warrants
   it.
2. **Smallest emitter slice — fixed prefix + UTF-8 body, `BackPatch`
   path.** Target: `MqttProtocolName` fixture (or whatever the
   chosen vector is). Emit the codec; encode uses the runtime's
   string-write API to land bytes directly into the buffer; decode
   reads the prefix then exactly `len` UTF-8 bytes via the
   runtime's string-read API. Default `WireSize.BackPatch` (or
   `Exact` if the runtime can compute UTF-8 byte length without
   allocating).
3. **Empty / ASCII / multi-byte round-trip cases** — the contract
   tests for the Stage C slice. Expect the allocation tracker (or
   zero-`ByteArray` enforcement mechanism) to be wired here.
4. **Full check** — `./gradlew :buffer-codec-processor:test
   :buffer-codec-test:jvmTest` green; the new fixture's tests pass;
   existing 45 cases regression-free. Stage C is closeable when this
   is green.

Open questions to address while sequencing:

- Does the emitter need a separate `BackPatch` codepath (with
  `GrowableWriteBuffer`), or can it use a two-pass strategy:
  compute UTF-8 byte length first, write prefix, then write body —
  treating the result as `Exact`? The runtime's `writeString` may or
  may not return the byte count; if it does, single-pass `Exact` is
  achievable. If not, `BackPatch` with grow-and-patch is needed.
- How does `@LengthPrefixed` interact with `@WireOrder` on the
  prefix bytes? Already locked in Stage A: prefix bytes follow
  message-level wireOrder. Same rule applies for String body's
  prefix.
- The String *body* itself has no byte order (UTF-8 is a byte
  sequence). No `@WireOrder` interaction on the body bytes
  themselves.

## Carryovers (still in force)

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- mavenLocal republish stays deferred until after Stage H.
- Apple build verification carryover from commits `f0a68a9` /
  `318b638` (touched `MutableDataBuffer` paths) — confirm on a macOS
  host before any merge to main. Not blocking Stage C.
- Eight `*_BUG.md` / `*_ISSUES.md` notes at repo root remain out of
  scope. Don't sweep them.
- Don't update `CLAUDE.md` or KDoc prose for slice semantics; the
  test suites are the executable doc. Annotation kdoc edits *are*
  in-bounds when they reflect a doctrine change.
- §8 / R1 / R3 / R4 validator rules stay. Stage C reintroduces the
  String body emission *alongside* the validator, not in place of
  it.
- **Stage-H follow-up** — the deferred bundle (MQTT
  `CorrelationData` / `AuthenticationData` migration to 2-field
  shape, R1 Payload exclusion removal, R3 widening to `@Payload`
  type parameters, `mqttPropertySize` deletion) is its own work.
  Stage C should *not* preempt Stage H's `@LengthPrefixed`-on-
  `@Payload` widening.

## Read these to load context

- `buffer/CLAUDE.md` — `BufferFactory` discipline, wrapper
  transparency, codec-using-protocol-codecs guidance, sealed dispatch
  patterns, `peekFrameSize` contract, `CodecContext` semantics.
- `PHASE_9_RESET.md` — Locked Decisions rows 1–13, Stages A–H plan,
  Deferred Decisions table.
- `PHASE_10_DESIGN_NOTES.md` — slice 4 walk for `@LengthPrefixed`
  semantics; the doctrine derivation history.
- `buffer-codec-test/.../riff/{RiffChunkHeader.kt, WavFmtChunk.kt}`
  — Stage A reference data classes (already emitted).
- `buffer-codec-test/.../dns/DnsHeader.kt`,
  `.../flv/FlvTagHeader.kt`, `.../mysql/MySqlPacketHeader.kt` —
  Stage B reference fixtures (already emitted). The MySQL value-
  class-of-raw shape is the canonical example for fixed bit-packed
  headers.
- `buffer-codec/.../Annotations.kt` — annotation surface; Stage C
  will exercise `@LengthPrefixed` on `String` fields (already
  declared accepted in the annotation kdoc).
- `buffer-codec-processor/.../CodecEmitter.kt` — the Stages A + B
  emitter. Stage C extends this file to handle `String` fields with
  `@LengthPrefixed`.
- `buffer-codec-processor/.../ProtocolMessageProcessor.kt` —
  validators (§8, R1, R3 implicit, R4). Stage C may add a new
  validator pass if the locked `LengthPrefix` shape introduces
  rules to enforce.
- `buffer/src/.../ReadBuffer.kt` and `.../WriteBuffer.kt` — string
  read/write APIs. Verify zero-`ByteArray` semantics on each
  platform implementation.

## Five most recent commits (local)

```
7592a14 Stage B: emit @WireBytes-narrowed scalars + value-class top-level codecs
fc426a2 Stage A: emit RIFF/WAV codecs from KSP, retire hand-written references
673e464 docs: lock Phase 10 wire-mirroring carve-out and retire resume briefing
377b561 buffer-codec-test: redesign slice 4 with @LengthPrefixed body (R3)
1ef5f31 buffer-codec-processor: add Phase 10 R1 (scoped adjacent-@LengthFrom rejection)
```

## When this file's job is done

Delete `STAGE_C_RESUME.md` in the same commit that closes the first
Stage C slice (whichever fixture is emitted first). The locked
decisions absorbed in Stage C get added to `PHASE_9_RESET.md` as new
Locked Decisions rows; this ephemeral hand-off has no further role.
