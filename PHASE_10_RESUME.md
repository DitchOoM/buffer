# Phase 10 — next-session briefing (delete after consumed)

> **Ephemeral.** Hand-off from the slice-4 redesign + §8 validator
> session (commits `2bbc580` and `1be3bb3`) to a re-review of slice
> 4's data-class shape. Once read and resolved, delete this file in
> the same commit that closes the question.

Resuming Phase 10 codec rewrite on the buffer repo at
`/home/rbehera/git/buffer`, branch `feature/directional-codec`.

## TL;DR

Slice 4 was redesigned in `2bbc580` to drop the §8-violating opaque
`ChunkBody(ReadBuffer)` body in favor of a typed `WavFmtBody`. That
landed green and the §8 validator (`1be3bb3`) prevents the original
mistake from recurring. **No new code should land on
`feature/directional-codec` until the question below is resolved.**

The redesign exposed a new concern: `WavFmtChunk` carries both
`chunkSize: UInt` and `body: WavFmtBody`, which independently encode
the body length. The data class permits constructible-but-invalid
combinations (e.g., `WavFmtChunk(fmtTag, 17u, pcmBody)`) — a
representable state with no valid wire. The locked slice-4 design
chose `@LengthFrom("chunkSize")` deliberately, so this is **not a
bug to silently fix** — it's a question whose answer changes the
locked annotation surface.

## Why this matters

The Kotlin model of a wire format ideally makes illegal states
unrepresentable. Today's `WavFmtChunk` doesn't:

```kotlin
@ProtocolMessage(wireOrder = Endianness.Little)
data class WavFmtChunk(
    val fourCC: UInt,
    val chunkSize: UInt,                              // wire length, 32 bits
    @LengthFrom("chunkSize") @UseCodec(WavFmtBodyCodec::class)
    val body: WavFmtBody,                             // typed, fixed 16 bytes
)
```

`chunkSize` and `body.wireSize()` independently encode the same
quantity. The user can construct `WavFmtChunk(fmtTag, 17u, pcmBody)`,
encode produces mis-framed wire, decode of that wire sets
`setLimit(position + 17)` and calls `WavFmtBodyCodec` which only
reads 16 — leaving 1 byte un-consumed inside the bound, then the
outer-limit restore swallows it on the way past.

The session preceding this one floated **widening `@LengthPrefixed`**
as a fix:

```kotlin
@ProtocolMessage(wireOrder = Endianness.Little)
data class WavFmtChunk(
    val fourCC: UInt,
    @LengthPrefixed(LengthPrefix.Int) val body: WavFmtBody,
)
```

— the prefix is emitted from `WavFmtBodyCodec.wireSize()` on encode,
read and used as a bound on decode. The data class no longer carries
`chunkSize`, so the impossible-state class disappears.

## Why this is not a session-local edit

The `@LengthFrom("chunkSize")` choice is **locked** in the slice 4
walk of `PHASE_10_DESIGN_NOTES.md` (line ~1267 onwards) on the basis
that the data class should mirror the wire format one-to-one — every
wire field becomes a constructor parameter, including length fields,
because that's what the model author writes when they read the spec.

Widening `@LengthPrefixed` would override that locked decision. The
prefix becomes a property of the field's annotation, not a sibling
constructor parameter, and the wire format no longer mirrors the
data class one-to-one. That is a design-rule change, not a slice-
local cleanup.

The previous session also hit the "design notes are archival" rule:
we don't extend or rewrite the slice 4 walk. If the locked decision
actually changes, the design-notes update is a single, surgical
correction note inside the slice 4 walk — and the call is the
user's, not a session side-effect. **The next session must not
modify `PHASE_10_DESIGN_NOTES.md` or `PHASE_9_RESET.md` without
explicit user authorization.**

## What the next session must do — questions for the user, in order

Walk these one at a time. Wait for the user's answer to each before
asking the next. **Do not write code or touch design notes until all
five are answered.**

### Q1 — semantic scope of `@LengthPrefixed`

`@LengthPrefixed` today targets `String` and is documented as "prefix
bytes followed by UTF-8 data." The proposed widening targets any
`@ProtocolMessage` data class field (and, eventually, any `Payload`
subtype). Does the user read this as a *natural extension* of the
existing semantics ("any field whose wire form is `length || value`")
or as a *category change* (string handling vs. message-typed bodies
are conceptually different)?

If the latter, a new annotation may be the better path; if the
former, widening is preferred.

### Q2 — wire-mirroring as a hard rule

The slice 1 audit locked "data class mirrors wire format one-to-one"
as a readability principle: every numeric wire field maps to a
constructor parameter. Widening `@LengthPrefixed` breaks that — the
length prefix is no longer a constructor parameter, it's implicit in
the field's annotation.

Is one-to-one wire-mirroring intended to apply to *every* numeric
wire field, or only to fields the consumer cares about reading
directly (i.e., is "redundant length carrier" exempt)? The answer
governs whether widening is even on the table.

### Q3 — overlapping or exclusive

If `@LengthPrefixed` is widened to cover `@ProtocolMessage` fields,
does `@LengthFrom` keep both the *remote-prefix* case (length
carried in a remote header, e.g., MQTT fixed header) *and* the
*local-prefix* case (length immediately precedes the field, the
RIFF case), or does the local-prefix case migrate exclusively to
`@LengthPrefixed`?

Overlap is more flexible but means two annotations can express the
same wire shape — a redundancy that has its own readability cost.

### Q4 — gap-acceptance fallback

If the user prefers to keep `@LengthFrom` as-is and not widen
`@LengthPrefixed`, is the impossible-state class in `WavFmtChunk`
acceptable as a known limitation while Stage A–H proceeds, with a
single one-line correction note in the design-notes slice 4 walk
recording that the gap was reviewed and accepted?

The codec only validates byte layout; protocol-level field
consistency (`byteRate`, `audioFormat` enum bounds, etc.) is already
out of scope. The user may judge `chunkSize` redundancy as the same
class of acceptable gap.

### Q5 — landing strategy

If the user picks "widen `@LengthPrefixed`," does the change land
as:

  - **a new slice (4.5)** between the current slice 4 and slice 5,
    with its own design-notes walk, locks, and proof vector; OR
  - **an amendment to slice 4** — re-redesign `WavFmtChunk`, replace
    the `@LengthFrom`/`@UseCodec` pair on `body` with
    `@LengthPrefixed(LengthPrefix.Int)`, update `WavFmtChunkCodec` and
    its tests, and add a single correction note to the slice 4 walk
    recording the change.

Slice 4 has now been redesigned twice — the first time correctly
(typed body), the second would be a wire-format-mirroring change.
The user may judge that the third revision deserves its own slice
to keep the audit trail clean, or that amending in place is fine.

## Carryovers from the prior briefing

These constraints from the previous session still hold:

- DO NOT push commits until the user confirms.
- DO NOT skip git hooks (`--no-verify`, `--no-gpg-sign`).
- DO NOT modify `PHASE_10_DESIGN_NOTES.md` or `PHASE_9_RESET.md`
  without explicit user authorization, and even then only as a
  surgical correction note — not a rewrite.
- mavenLocal republish stays deferred until after Stage H.
- Apple build verification carryover from commits `f0a68a9` /
  `318b638` (touched `MutableDataBuffer` paths) — confirm on a
  macOS host before any merge to main. Not blocking.
- Eight `*_BUG.md` / `*_ISSUES.md` notes at repo root are still out
  of scope. Don't sweep them.
- Don't update `CLAUDE.md` or KDoc prose for slice semantics; the
  test suites are the executable doc.
- §8 validator is now in place
  (`buffer-codec-processor:ProtocolMessageProcessor`). Any future
  raw-bytes-in-fields mistake fails the build at compile time.
  Don't add `Payload` machinery prematurely; that's still Stage H.

## Read these to load context

- `PHASE_9_RESET.md` — locked decisions, Stages A–H plan, constraints.
- `CLAUDE.md` — `BufferFactory` discipline, wrapper transparency,
  generated `peekFrameSize` contract.
- `PHASE_10_DESIGN_NOTES.md` slice 4 walk (line ~1267) — the
  `@LengthFrom("chunkSize")` lock and its rationale.
- `PHASE_10_DESIGN_NOTES.md` §8 (line ~198) — `Payload` marker.
  Already enforced by the validator; mentioned here for context.
- Commit `2bbc580` — slice 4 redesign with typed body
  (`WavFmtChunk` + `WavFmtBody`).
- Commit `1be3bb3` — §8 validator + kctfork bump (kctfork
  0.12.0-alpha01, kotlin-compiler-embeddable pinned to 2.3.0 final).
- Commit `f658120` — slice 1 (`RiffChunkHeader`).

## Five commits since the last carry-over

```
1be3bb3 buffer-codec-processor: enforce §8 raw-bytes ban in @ProtocolMessage fields
2bbc580 buffer-codec-test: redesign slice 4 with typed WAV fmt body
6ffead9 buffer-codec-test: type-check slice 4 (THE ORIGINAL — now superseded)
897df44 docs: add PHASE_10_RESUME.md briefing for next session
318b638 PlatformBuffer.slice(): narrow return type to PlatformBuffer
```

(`6ffead9` is the commit `2bbc580` redesigned. `897df44` introduced
the prior briefing; deleted in `6ffead9`. This file replaces it.)

## When this file's job is done

Delete `PHASE_10_RESUME.md` in the same commit that closes the
question (whichever path the user picks). The information is either
absorbed into commit history (the slice 4 v3 / amendment / new
slice 4.5), into a single correction note in the archival design
notes (the gap-acceptance path), or no longer relevant once the
discussion concludes.
