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

## Non-goals

- Bug-for-bug compatibility with the legacy emitter's output shape.
  Generated code can change as long as wire format stays correct.
- Preserving the 161-commit narrative as separate commits on the new
  direction — squash-and-merge at the end is fine.
- Touching mqtt or websocket repo histories. They stay frozen until the
  codec is solid; then they get from-scratch cutover commits.

## Next steps

- `:buffer-codec-test` rebuilds against the reverted `:buffer-codec` /
  `:buffer-codec-processor` (= `main` at `676d53f`). All tests green
  before any new work lands.
- Re-land the contract-inversion commits (Steps 8.1, 8.2, 8.3, 8.7, 8.8
  in this branch's prior history) as a small replayed sequence. They're
  additive and don't depend on the processor rewrite.
- Move the v4 / v5 / WebSocket / RIFF model classes + their round-trip
  tests into `buffer-codec-test/commonMain` as fixtures. (Module split
  deferred.)
- Then start the single-strategy processor rewrite, IR-first, gated on
  `:buffer-codec-test:jvmTest` green per commit.

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
  currently holds a stale jar (rebuilt from Step 5 source) that
  doesn't satisfy mqtt. After this revert lands and the buffer rebuilds,
  republish to mavenLocal so mqtt picks up the reverted artifact.
