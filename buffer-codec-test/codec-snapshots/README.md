# Codec Snapshots

Checked-in baseline of every `.kt` file `buffer-codec-processor` emits for
the fixtures in this module. The mirror of
`build/generated/ksp/metadata/commonMain/kotlin/`.

`CodecSnapshotTest` (in `src/jvmTest`) diffs the live KSP output against
this tree on every `:buffer-codec-test:jvmTest` run.

## Why this exists

The v4 → v5.0 strip-and-rebuild of `buffer-codec-processor` silently
changed several emit shapes — nested codec naming regressed (issue #156),
the batching optimizer was deleted, the SPI extension point was removed.
Round-trip tests pass on *different but still valid* output, so none of
these surfaced until an external user hit one in production. Locking the
emit shape in a checked-in baseline makes every change a reviewable diff.

## Regen workflow

When you intentionally change codec emit shape (new feature, bug fix,
refactor in the emitter), regenerate the baselines and review the diff:

```
./gradlew :buffer-codec-test:jvmTest -Dupdate.snapshots=true
git add buffer-codec-test/codec-snapshots
git commit
```

The diff from that commit becomes the change's migration note. Reviewers
should be able to read it and understand what downstream codec users
will see differently.

## Don't edit these files by hand

These files are mechanical output of the KSP processor. If something here
looks wrong, fix the processor and regenerate — do not patch the snapshot
directly. A snapshot edited by hand will drift from generated output on
the next regen and the diff will be confusing.

`ktlint` is configured to skip this directory (see `build.gradle.kts`).
