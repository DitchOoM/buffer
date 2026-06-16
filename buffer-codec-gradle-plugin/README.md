# buffer-codec-schema Gradle plugin

A build-time **wire-compatibility gate** for [`buffer-codec`](../buffer-codec) protocol types. It
baselines the wire shape of every `@ProtocolMessage` type into a diffable file you commit, then
fails (or warns on) the build when a change would break peers already on the wire.

Plugin id: `com.ditchoom.buffer.codec-schema`

## Why you want this

`@ProtocolMessage` encodes **positionally** — fields ride the wire in constructor order, enums as
their `ordinal`, sealed variants as their `@PacketType` / `@DispatchValue` discriminator. That makes
a whole class of edits silently wire-breaking:

- **Reorder or insert an enum entry** → every `ordinal → meaning` shifts; peers misread every value.
- **Insert or delete a field** → every later field shifts; framing breaks for old peers.
- **Change a field's wire width or byte order** (`@WireBytes`, `@WireOrder`) → silent corruption.
- **Reassign a `@PacketType` / `@DispatchValue`** → variant dispatch breaks.
- **Remove `@EnumDefault` / `@ForwardCompatible`** → unknown values now throw instead of resolving.

Round-trip tests **cannot** catch these — you encode and decode with the same new code, so the test
passes against different-but-self-consistent bytes. This plugin compares your **current** generated
wire shape against a **committed baseline** and tells you, at build time, when the bytes changed
meaning for someone who lacks your new code.

## Setup

You should already be generating codecs with `buffer-codec` + the KSP processor. Add the plugin:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
    id("com.ditchoom.buffer.codec-schema") version "<latest-version>"
}

dependencies {
    implementation("com.ditchoom:buffer-codec:<latest-version>")
    ksp("com.ditchoom:buffer-codec-processor:<latest-version>")
}

codecSchema {
    // Defaults shown — both are optional.
    baseline.set(file("src/codecSchema/codec-schema.txt"))
    failOnBreaking.set(false) // warn-only by default; set true to fail the build on breaking drift
}
```

The plugin version tracks the `buffer-codec` version — use the same one.

## First build: create the baseline

The processor emits a `codec-schema.txt` descriptor next to your generated codecs. On the first
`check` with no baseline yet, the plugin writes that descriptor to `baseline` and asks you to commit
it:

```
$ ./gradlew check
> Task :checkCodecSchema
codec schema baseline created at src/codecSchema/codec-schema.txt — commit it to source
control so future builds can detect wire-breaking drift.
```

```bash
git add src/codecSchema/codec-schema.txt
git commit -m "codec schema baseline"
```

From now on, every `check` diffs the freshly-generated descriptor against this committed file.

## Tasks

| Task | What it does |
|------|--------------|
| `checkCodecSchema` | Diffs the generated descriptor against the baseline and classifies drift. **Wired into `check`.** |
| `updateCodecSchema` | Overwrites the baseline with the freshly-generated descriptor — the deliberate "I meant to change this" gesture. |

## How drift is classified

| Change | Severity | Build result |
|--------|----------|--------------|
| Append an enum entry / message field / new dispatch value at the end | **safe** | passes silently |
| Add `@EnumDefault` / `@ForwardCompatible` (widens forward-compat) | **safe** | passes silently |
| Rename a field/entry/variant in place, wire shape unchanged | **advisory** | always warns, **never fails** (even under `failOnBreaking`) |
| Reorder / insert / delete an enum entry or field | **breaking** | warns; fails under `failOnBreaking` |
| Change a field's type, width, byte order, or framing | **breaking** | warns; fails under `failOnBreaking` |
| Reassign or remove a `@PacketType` / `@DispatchValue` | **breaking** | warns; fails under `failOnBreaking` |
| Remove `@EnumDefault` / `@ForwardCompatible`, or change a `@FramedBy` codec | **breaking** | warns; fails under `failOnBreaking` |

A rename is only **advisory** because the wire never carries names — but the differ can't tell a pure
rename from a disguised semantic change, so it asks you to confirm. Accept intentional renames (and
any other reviewed change) with `updateCodecSchema`.

## Day-to-day workflow

**Made an intentional, reviewed wire change?** Re-accept the baseline and commit the diff — the diff
*is* your migration note:

```bash
./gradlew updateCodecSchema
git add src/codecSchema/codec-schema.txt
git commit
```

**Build failed on breaking drift?** Either it's a mistake (fix the type), or it's intentional (run
`updateCodecSchema`). A breaking warning looks like:

```
codec schema drift (breaking): com.acme.proto.Intensity
  [breaking] enum entry 'Bold' moved from ordinal 1 to 2 — reordering/inserting changes the
  meaning of bytes already on the wire
(set codecSchema.failOnBreaking = true to make breaking drift fail the build)
```

## Notes

- The descriptor is generated from **source** at KSP time and committed to VCS, so R8/ProGuard,
  obfuscation, and minification are irrelevant to it.
- For Kotlin Multiplatform, the plugin locates the descriptor by scanning the `build/generated/ksp`
  output, so it works regardless of which source set your `@ProtocolMessage` types live in (common
  types land under `metadata/commonMain`). A single aggregate baseline covers the module.
- This gate is **complementary** to the runtime forward-compatibility annotations (`@EnumDefault`,
  `@ForwardCompatible`, `@UnknownVariant`): those let a message *tolerate* unknown values at runtime;
  this detects, at build time, when you've *broken* the schema for peers that lack those tolerances.
