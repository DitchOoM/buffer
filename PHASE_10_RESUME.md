# Phase 10 — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the slice-normalization arc (this
> session) to the codec-emitter rewrite (next session). Once read
> and started, delete this file in the same commit.

Resuming Phase 10 codec rewrite on the buffer repo at
`/home/rbehera/git/buffer`, branch `feature/directional-codec`.

## Read these first to load context

- `PHASE_9_RESET.md` — locked decisions, Stages A–H execution plan,
  the constraints that survive every reset (no push, no `--no-verify`,
  mavenLocal cutover deferred until after Stage H, models bend to the
  codec).
- `CLAUDE.md` — project rules. Pay attention to: zero `ByteArray` in
  production paths, `BufferFactory` (never `PlatformBuffer.allocate`),
  `@ProtocolMessage` over hand-written encode/decode, wrapper
  transparency, generated `peekFrameSize` contract.
- `PHASE_10_DESIGN_NOTES.md` — archival design walks. **Skim the
  opening note, then jump directly to slice 4 at line 1267.** The
  full doc is 3,832 lines and most of it is superseded by the
  architectural pivot in the prior briefing — but slice 4's vector,
  data-class shape, and validation walk are the spec for the work
  starting now.
- `buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/riff/`
  — the slice-1 hand-emitted codec. `RiffChunkHeader` (data class +
  `@ProtocolMessage`) and `RiffChunkHeaderCodec` (hand-emitted
  reference impl). This is the proof-of-concept the slice-4 work
  extends into a framed body via `@LengthFrom` + `@UseCodec`.

## What landed since the prior PHASE_10_RESUME.md

Five commits, all in service of locking slice semantics so the codec
emitter has a uniform foundation to target:

```
318b638 PlatformBuffer.slice(): narrow return type to PlatformBuffer
f0a68a9 slices: make MutableDataBufferSlice + NativeBufferSlice writable
8dd6627 FragmentedReadBuffer: return first.byteOrder instead of throwing
2d371bc buffer-compression(linux): honor arrayOffset in pinning paths
e4b5acc ByteArrayBuffer: zero-copy slice sharing the backing array
```

**Net effect on the codec contract:**

- `PlatformBuffer.slice(byteOrder): PlatformBuffer` — covariant
  override on the interface. Every PlatformBuffer-backed slice is
  guaranteed writable + zero-copy at the type level. The emitter
  can call `parent.slice(fieldByteOrder)` and rely on a writable
  view without runtime `is WriteBuffer` checks.
- `ByteArrayBuffer` slice now shares the backing array via
  internal `offset`/`length` ctor params (matching JVM
  `HeapJvmBuffer` semantics). `arrayOffset` returns the slice
  offset.
- `MutableDataBufferSlice` (Apple) and `NativeBufferSlice` (Linux)
  are now `: PlatformBuffer` with full write methods that
  propagate to the parent's underlying memory.
- `NSDataBuffer` / `NSDataBufferSlice` stay `: ReadBuffer` —
  intentionally read-only (NSData is immutable). The rule is now
  uniform: a slice is writable iff its parent is.
- `PooledBuffer.slice()` returns `PlatformBuffer`. `TrackedSlice`
  collapsed to a single `: PlatformBuffer by inner` class — pool
  consumers get writable slices automatically.

## First concrete action this session

**Slice 4 type-check** — `RIFF chunk + @LengthFrom + @UseCodec`.

The vector is in `PHASE_10_DESIGN_NOTES.md` starting at line 1267.
Mirrors the slice-1 pattern: write the data classes against the
spec, hand-emit the codec as if the processor had generated it,
wire up the round-trip test in `buffer-codec-test`. The hand-
emitted codec is the type-check artifact — it proves the design's
field annotations compose against the real `Codec`/`CodecContext`
runtime types before any KSP work.

Concrete fragments to land:

- `RiffChunk` data class — `fourCC: UInt`, `chunkSize: UInt`,
  `@LengthFrom("chunkSize") @UseCodec(RawChunkBodyCodec::class)
  val body: ChunkBody`. Lives next to the existing
  `RiffChunkHeader.kt` in `buffer-codec-test/src/commonMain/.../riff/`.
- `ChunkBody` value class wrapping a `ReadBuffer` (zero-copy body
  view, deferred parse).
- `RawChunkBodyCodec` — hand-emitted `Codec<ChunkBody>` that reads
  `remaining()` bytes from a pre-bounded slice. The processor
  contract is that `@LengthFrom` bounds the buffer *before* the
  user codec is called, so `RawChunkBodyCodec.decode` doesn't need
  to know the length.
- `RiffChunkCodec` — hand-emitted parent codec that decodes the
  header fields, computes the body bound from `chunkSize`, calls
  `buffer.slice(byteOrder = parent.byteOrder)` (now a writable
  `PlatformBuffer` end-to-end), invokes the body codec, and
  returns. Validates the slice-bounding sketch in slice 4's notes.
- Round-trip test: hand-crafted RIFF chunk byte vector, decode to
  `RiffChunk`, encode back, byte-equal assertion.

The locks slice 4 is supposed to drive (per PHASE_9_RESET decisions
table): `@LengthFrom` resolution form (string DSL vs property
reference vs compile-time resolved name), and the `@UseCodec`
boundary contract. Don't try to settle these before writing the
codec — let the hand-emit force the shape.

## After slice 4 lands cleanly

Slice 5 (`@RemainingBytes` on a WebSocket close-frame body — line
1503 of the design notes) is the natural next step. The
`setLimit + restore` bounding pattern from slice 4 reuses, so
slice 5 should be a smaller increment.

## Don't touch these (out of scope; let the slice work proceed)

- **Apple build verification carryover.** This session's commits
  `f0a68a9` and `318b638` touch `MutableDataBuffer.kt` /
  `MutableDataBufferSlice` — code paths that compile only on a
  macOS host. The shapes mirror the verified Linux changes
  (`NativeBufferSlice`), but a CI or local macOS run should
  confirm before any merge to main. Not blocking for slice 4 work.
- **Eight untracked `*_BUG.md` / `*_ISSUES.md` notes at repo root**
  (ANDROID_BUFFER_ISSUES, BOUNDS_CHECK_REGRESSIONS, etc.). Unclear
  if active or stale. Don't sweep them in this session; revisit
  when the codec is back to a known-green state.
- **Don't update `CLAUDE.md` or KDoc prose for slice semantics.**
  The slice-normalization arc concluded with prose still saying
  the old story in some places. The `BufferSliceByteOrderBehavior
  Tests` are the executable documentation; durable doc updates
  can land when the codec rewrite next touches that area
  organically.
- **Don't extend `PHASE_10_DESIGN_NOTES.md`** — it's archival.
  Slice-4 findings live in commit messages and code comments; the
  authoritative spec `PHASE_10_DESIGN.md` (still TBD) gets written
  after the next batch of slices land.

## When this file's job is done

Delete `PHASE_10_RESUME.md` in the same commit that closes slice 4.
The information is either absorbed into commit history (the codec
itself), into the archival design notes (slice-4 findings), or no
longer relevant (the briefing).
