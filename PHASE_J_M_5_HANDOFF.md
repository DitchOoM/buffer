# Phase J.M.5 — MQTT v5 modeling — handoff

This file is the next-session prompt. Read it, then read the docs it
points to, then propose a slice plan before writing code.

## Prompt

Start Phase J.M.5 — MQTT v5 modeling.

Read in order: `PHASE_J_M_BRIEF.md` (especially §2 "v3.1.1 vs v5.0
wire format" at line 229+ and the J.M.5 sequencing notes at 284-291,
409-412), `PHASE_I_1_RESUME.md` "Step 11 — landed" section for the
`@LengthPrefixed @UseCodec` property-bag shape now available,
`STAGE_H_RESUME.md` §"Slice 10d" and §"Slice 10f" for the dispatcher
+ outer-limit machinery v5 packets compose with.

Confirm green baseline before starting:

```
:buffer-codec-test:jvmTest    = 342
:buffer-codec-processor:test  = 56
:buffer-flow:jvmTest          = 36
```

Branch HEAD is `2b235213` (Phase I.1 step 11 fixtures) on top of
`eda80479` (step 11 processor changes). Phase I.1 is complete; the
v5 property-bag shape is the new capability this session uses.

### Step 1 — survey what v5 needs vs what v3.1.1 already has

- Read `/home/rbehera/git/MQTT_V5_CODEC_MIGRATION_PLAN.md` for the
  v5 wire-format spec and existing handwritten v5 code, if any.
- Skim `/home/rbehera/git/V5_HANDWRITTEN_AUDIT.md` for the gap
  analysis.
- Review the current `MqttPacket` sealed parent in
  `buffer-codec-test/.../mqtt/MqttPacket.kt` — that's the v3.1.1
  shape Phase J.M built. v5 packets should sit alongside, NOT
  replace, the v3 variants (they're separate wire protocols
  selected at connect time).

### Step 2 — propose a slice plan

Don't start coding until we agree on:

1. **Where v5 lives.** New sealed parent `MqttV5Packet`?  Variant
   additions to `MqttPacket` with a version discriminator?  Separate
   fixture file?  My initial lean: separate sealed parent + separate
   dispatcher — v5's wire format isn't a superset of v3, and a unified
   type would smear the validator's per-variant uniqueness checks.

2. **Which packets to land first.** CONNECT v5 has the most surface
   (property bag in 4 places + will-message); PINGREQ/PINGRESP are
   trivial smoke tests. PUBLISH v5 exercises step 11 (the property
   bag) AND the slice 10c/10f Partial machinery simultaneously — the
   highest-coverage smoke test.

3. **`MqttProperty` modeling.** v5 has ~30 property identifiers each
   carrying a typed value (UByte/UInt/UShort/binary/string/UTF-8
   string pair). Likely a sealed parent with `@PacketType` dispatch
   on the property-id byte, similar to the existing `MqttPacket`
   dispatcher. Confirm whether the existing `@DispatchOn` machinery
   handles a 1-byte UByte discriminator directly or needs a
   value-class wrapper.

Report a slice plan in under ~400 words with specific file paths and
the smallest first commit. Land nothing until I agree on the plan.

### Expected first slice (after plan agreement)

A single CONNECT v5 OR PINGREQ v5 fixture as a smoke test, exercising
the new property bag shape end-to-end (encode + decode + peek +
round-trip), with one or two `MqttProperty` variants only (not the
full ~30). The rest grows from there.

## Why "propose a plan first"

v5's scope is large enough that locking the file/type layout up
front saves a refactor later. The v3 fixture structure (single
sealed parent, per-variant nested data classes, dispatcher-driven
peek) was a contestable design decision; v5's property-bag-everywhere
shape pushes hard enough on that structure that re-litigating it now
is cheaper than after 30 property variants exist.
