# Phase 10 — next-session briefing (delete after consumed)

> **Ephemeral.** This file exists to hand off context from one session
> to the next without re-deriving it. Once the next session has read
> it and started work, delete this file in the same commit.

Resuming Phase 10 codec rewrite on the buffer repo at
`/home/rbehera/git/buffer`, branch `feature/directional-codec`.

## Read these files first to load context

- `PHASE_9_RESET.md` — why the prior attempt failed; locked constraints.
- `PHASE_10_DESIGN_NOTES.md` — archival design walks (read the opening
  note, then skim; the doc is 3,832 lines and most of it is now
  superseded by the architectural pivot below).
- `CLAUDE.md` — project rules (zero ByteArray, BufferFactory, etc.).
- `TaskList` — pending tasks `#16`, `#17`, `#18`, `#19`, `#20`, `#21`,
  `#22`, `#24` are open.

## Read these to confirm current state

- `git log --oneline -12` — last session landed 8 commits (rename →
  short-form read aliases → slice 1 type-check → `slice(byteOrder)` API +
  cross-platform tests → deeper slice behavior tests + classification).
- `buffer-codec-test/src/commonMain/kotlin/com/ditchoom/buffer/codec/test/protocols/riff/`
  — slice 1 hand-emitted codec proves the slice sketches in the notes
  are implementable against the real APIs.
- `buffer/src/commonTest/kotlin/com/ditchoom/buffer/BufferSliceByteOrder*.kt`
  — 24 tests, 4 platforms, 96 passing assertions documenting slice
  semantics. The classification test names a pre-existing divergence
  across backends (`ZERO_COPY`, `COPY`, `READ_ONLY`).

## Architectural pivot from last session

(Load this as context, not detail yet — the new `PHASE_10_DESIGN.md`
will formalize.)

- **Two axes**: structural annotations (compile-time shape) vs codec
  wrappers (runtime wire transforms). Today's 11 annotations are the
  right structural surface; XOR mask, MQTT VBI, alignment pad, MQTT v5
  property list, WebSocket cascading length, bit-packed shared bytes
  all become framework-provided codec wrappers reached via `@UseCodec`.
  No new annotations needed — that resolves the WebSocket / RIFF / MQTT v5
  gaps the parallel reviewer agents flagged.
- **Codec emitter rule**: parent-direct field reads use manual byte
  assembly when wire order ≠ buffer order; sub-codec boundaries use
  `buffer.slice(fieldByteOrder)` so the sub-codec uses natural
  primitives. `buffer.slice(byteOrder)` lands as a single function
  with default = `parent.byteOrder`; verified across 4 platforms.

## First concrete action this session

**Task `#24` — make `ByteArrayBuffer.slice()` zero-copy.** Single
focused refactor:

- Add internal `offset: Int = 0` and `length: Int = data.size`
  constructor params.
- Every `data[i]` access becomes `data[offset + i]`.
- `arrayOffset` returns `offset` (matches JVM `HeapJvmBuffer`
  semantics).
- `capacity` becomes `length`, not `data.size`.
- `slice()` creates a new `ByteArrayBuffer` sharing `data` with
  adjusted `offset` + `length`.
- Re-enable the 3 currently-skipped write-through tests for
  `ByteArrayBuffer`-backed factories.
- Run `:buffer:check` on all 4 reachable platforms (JVM, JS, WASM,
  Linux native).

## After `#24` lands cleanly

Decide whether to also normalize the `READ_ONLY` divergence — Apple
slice classes (`MutableDataBufferSlice`, `NSDataBufferSlice`) and Linux
`NativeBufferSlice` don't implement `WriteBuffer`. The `slice()`
interface contract returns `ReadBuffer`, so writability is a
backend-specific extension. Question: should slices uniformly
implement `WriteBuffer` when the underlying buffer is mutable?

Then proceed with **task `#16` — slice 4 type-check** (RIFF chunk +
`@LengthFrom` + `@UseCodec`), now over uniform slice semantics.

## Don't do these (deferred intentionally)

- **Don't update KDocs or `CLAUDE.md` for slice semantics yet.** The
  divergence is being fixed, so any prose now becomes immediately
  stale. The classifier test in `BufferSliceByteOrderBehaviorTests`
  is the executable documentation. Update durable docs once slice
  semantics are uniform (after `#24` + the `READ_ONLY` normalization
  decision).
- **Don't extend `PHASE_10_DESIGN_NOTES.md`** — it's archival. The
  new authoritative spec `PHASE_10_DESIGN.md` gets written after
  task `#24`, informed by the slice 1 type-check findings already in
  hand.

## When this file's job is done

Delete `PHASE_10_RESUME.md` in the same commit that closes task `#24`.
The information has either been absorbed into commit history (the
refactor itself), into `PHASE_10_DESIGN.md` (the architectural pivot),
or is no longer relevant (the briefing).
