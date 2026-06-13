# HTTP/3 varint framing — two declarative gaps blocking the socket migration

**Status:** spec (for implementation) · **Track:** `codec/varint-h3` · **Date:** 2026-06-05

## Context

The `com.ditchoom:socket` library is migrating its hand-written protocol codecs
onto the buffer-codec KSP processor (socket branch `feat/buffer-codec-migration`).
The first body — `Http3Setting` (two QUIC varints) — migrated cleanly via
`@ProtocolMessage` + `@UseCodec(VarIntCodec)`. The next target is the **full
`Http3FrameCodec`** (the RFC 9114 §7.1 frame envelope: `Type (varint)` +
`Length (varint)` + payload), and it is **blocked**: two of its load-bearing
behaviors cannot be expressed declaratively today. This doc specifies both gaps
and proposes the framework features that would unblock a **byte-identical** full
migration.

The reference fixture `buffer-codec-test/.../protocols/http3/Http3Frame.kt`
already exercises varint dispatch + a `BoundingLengthCodec` length — but it keeps
**every payload opaque** and **stores `length` as a field**, and it has **no
unknown-frame handling**. socket's real codec is richer than that fixture in
exactly the two dimensions below, which is why the fixture compiles but the real
migration cannot.

Both gaps share a root cause: the framework's variable-width (varint) discriminator
and length support is newer than its fixed-width support, and two features that
exist for fixed-width discriminators have not been extended to the varint case.

---

## Gap 1 — varint-discriminated unknown-frame capture (skip-and-preserve)

### What socket needs

RFC 9114 §9 requires a receiver to **ignore unknown frame types** (reserved /
GREASE types of the form `0x1f·N + 0x21`), not fail. socket models this as a
sealed variant:

```kotlin
data class Unknown(val type: Long, val payload: ReadBuffer) : Http3Frame
```

On decode, an unrecognized frame **type** varint + its length-bounded payload are
captured into `Unknown`; on encode they round-trip byte-for-byte. This is
**interop-load-bearing** — the socket H3 stack is proven against Cloudflare and
Google, both of which send GREASE frames. A codec that throws here is a
functional regression.

### Why it's not expressible

- The generated varint `@DispatchOn` dispatcher's `else` arm **hard-throws**
  `DecodeException`. Confirmed in the generated reference codec
  `buffer-codec-test/build/.../http3/Http3FrameCodec.kt`:
  `else -> throw DecodeException(fieldPath = "Http3Frame.discriminator", ...)`.
- The only skip-and-preserve mechanism, `@ForwardCompatible`, is validator-gated
  to a **single-byte** (Byte / UByte) discriminator. See **F2** in
  `ProtocolMessageProcessor.kt` `validateForwardCompatible` (~line 1910–1957):

  ```
  // Single-byte inner scalars only (Byte / UByte). Wider discriminators would
  // need a multi-byte opcode store + a byte-order-aware re-encode, neither of
  // which the preserve path emits today.
  if (innerQname != null && innerQname !in SINGLE_BYTE_SCALAR_QNAMES) { logger.error(...) }
  ```

  HTTP/3 frame types are varints (`@UseCodec(VarIntCodec) raw: Long`), so
  `@ForwardCompatible` is rejected at compile time.

### Proposed feature

Lift `@ForwardCompatible` to support a **varint `@DispatchOn` discriminator**:

- Store the preserved opcode as the discriminator's **own decoded value**
  (`Long` / `ULong`) rather than a single byte, and re-encode it through the
  discriminator's `@UseCodec` codec (here `VarIntCodec`) — so a multi-byte GREASE
  type round-trips byte-identically. The F2 comment already names this exact
  missing capability ("multi-byte opcode store + byte-order-aware re-encode").
- Shape the `@UnknownVariant` sink to carry the wide opcode:
  `(opcode: Long, raw: ReadBuffer)` (or `ULong`) in addition to the existing
  `(opcode: Int, raw: PlatformBuffer)` form.
- This still requires `@FramedBy` (F1) so the skipped payload is length-bounded —
  which dovetails with Gap 2's computed varint length (below). The framing prefix
  is the frame's own `Length` varint.

Generalizes beyond H3: QUIC frame types, HTTP/3 **stream** types (§6.2), and
QPACK instruction opcodes are all varint-discriminated unions that want
ignore-unknown.

---

## Gap 2 — computed varint length prefix bounding an opaque / structured payload

### What socket needs

socket's model is **length-free**: no `length` field anywhere. The `Length`
varint is **computed** from the payload on encode and **consumed** (used to bound
the payload) on decode. The payloads are structured per type:

| Frame                              | Payload model (socket)                       |
|------------------------------------|----------------------------------------------|
| DATA / HEADERS                     | opaque `ReadBuffer`                           |
| SETTINGS                           | `List<Http3Setting>`  ✅ *expressible today*  |
| GOAWAY / MAX_PUSH_ID / CANCEL_PUSH | a single `Long` varint                       |
| PUSH_PROMISE                       | `pushId: Long` varint + opaque `ReadBuffer`  |
| Unknown                            | opaque `ReadBuffer` (+ Gap 1)                |

Keeping the model length-free matters: call sites construct `GoAway(streamId)`,
`Data(payload)` etc. with no length to compute or keep consistent. Storing a
`length` field (the reference-fixture shape) ripples length-correctness to every
call site (`Http3Connection`, `Http3ServerConnection`, `Http3LoopbackServer`) and
creates an impossible-state class (the stored prefix vs the body's real
`wireSize` independently encode the same quantity).

### Why it's not expressible (except SETTINGS)

The Length prefix is a **QUIC varint** (variable 1/2/4/8-byte width), and it must
be **computed from the body**. The available tools each fall short:

- **`@LengthPrefixed` (default)** emits a *fixed* 1/2/4-byte big-endian prefix
  (`LengthPrefix.Byte`/`Short`/`Int`) — wrong wire format vs a QUIC varint.
- **`@LengthPrefixed @UseCodec(varintPrefixCodec)`** routes `@UseCodec` to a
  varint `BoundingLengthCodec<UInt>` **prefix** codec *only for a `List<T>`
  field* (the MQTT-properties shape). For a single scalar/opaque payload,
  `@UseCodec` is interpreted as the **payload** codec and the prefix falls back
  to the fixed default. So:
  - **SETTINGS** ✅ works:
    `@LengthPrefixed @UseCodec(Http3VarintLenCodec) val entries: List<Http3Setting>`
    (computed varint prefix + by-convention `Http3SettingCodec` element).
  - **DATA / HEADERS / Unknown / GOAWAY / MAX_PUSH_ID / CANCEL_PUSH / PUSH_PROMISE**
    ❌ — opaque/scalar payloads have no `List` element codec to free up the
    `@UseCodec` slot for the varint prefix.
- **`@FramedBy(varintLenCodec, after = "frameType")`** on the sealed parent
  *would* give a computed, body-bounding prefix (length-free!) and is the natural
  pairing with Gap 1's `@ForwardCompatible`. But its **E3** validator
  (`validateFramedBy`, `ProtocolMessageProcessor.kt` ~line 1821–1848) requires the
  `after` field — the discriminator — to have **Exact wire width**. A varint
  `frameType` value class is variable-width; the slicing-scheme emitter
  ("right-flush the prefix against the body without shifting body bytes") assumes
  a fixed-width header preceding the prefix. So `@FramedBy` after a varint
  discriminator is untested/unsupported.

### Proposed feature (pick one; **B is preferred**)

**A. Computed varint prefix on a single payload field.** Allow
`@LengthPrefixed @UseCodec(C)` where `C : BoundingLengthCodec` to mean the
**prefix** codec even on a non-`List` field, with the payload codec supplied
separately (a second annotation, or a convention: the field type's by-convention
codec / a nested `@ProtocolMessage` body). Lets each variant model its payload
as a nested length-free body bounded by a computed varint.

**B. `@FramedBy` with a variable-width discriminator.** Extend the `@FramedBy`
slicing scheme (and relax E3) so the `after` discriminator may be a
variable-width varint value class. The emitter measures the discriminator's
actual width at encode time (it already does for `Discriminator.Varint`) instead
of assuming Exact width. This is the cleanest fit: one annotation on the sealed
parent yields a **computed, body-bounding varint Length** for *every* variant,
keeps the model length-free, and is exactly what Gap 1's `@ForwardCompatible`
already requires (`@ForwardCompatible` mandates `@FramedBy`). **B closes most of
Gap 2 and pairs with Gap 1 in one stroke.**

With B, socket's variants become, e.g.:

```kotlin
@JvmInline @ProtocolMessage
value class Http3FrameType(@UseCodec(VarIntCodec::class) val raw: Long) {
    @DispatchValue val type: Int get() = raw.toInt()
}

@ProtocolMessage
@DispatchOn(Http3FrameType::class)
@FramedBy(Http3VarintLenCodec::class, after = "frameType")     // computed varint Length, body-bounding
@ForwardCompatible(unknown = Http3Frame.Unknown::class)         // Gap 1
sealed interface Http3Frame {
    @PacketType(0x00) @ProtocolMessage
    data class Data(val frameType: Http3FrameType = Http3FrameType(0),
                    @RemainingBytes @UseCodec(ReadBufferPayloadCodec::class) val payload: ReadBuffer) : Http3Frame

    @PacketType(0x04) @ProtocolMessage
    data class Settings(val frameType: Http3FrameType = Http3FrameType(4),
                        @RemainingBytes val entries: List<Http3Setting>) : Http3Frame

    @PacketType(0x07) @ProtocolMessage
    data class GoAway(val frameType: Http3FrameType = Http3FrameType(7),
                      @UseCodec(VarIntCodec::class) val id: Long) : Http3Frame

    @UnknownVariant
    data class Unknown(val opcode: Long, val raw: ReadBuffer) : Http3Frame
    // … Headers / MaxPushId / CancelPush / PushPromise analogously
}
```

(`Http3VarintLenCodec` is a `BoundingLengthCodec<UInt>` delegating to the QUIC
varint — socket can supply it; buffer ships no QUIC encoding. Note the `UInt`
type parameter of `BoundingLengthCodec` vs socket's `VarIntCodec : Codec<Long>`
is a minor adapter, but confirm `@FramedBy`/`@LengthPrefixed` can carry a
`ULong`/`Long`-valued length up to 2^62−1, not just `UInt` ≤ 4 GiB — H3 lengths
are 62-bit on the wire even if practically Int-bounded.)

---

## Acceptance criteria

The features land correctly when socket can, on its `feat/buffer-codec-migration`
branch:

1. Annotate `Http3Frame` (length-free, structural payloads, varint dispatch,
   `Unknown` capture) and have KSP generate `Http3FrameCodec`.
2. Pass a **differential test** vs the current hand-written codec:
   **byte-identical `encode`** and **decode parity** for every frame type —
   DATA, HEADERS, SETTINGS (multi-entry), GOAWAY, MAX_PUSH_ID, CANCEL_PUSH,
   PUSH_PROMISE, and at least two GREASE/Unknown types (1-byte and multi-byte
   varint type) — plus malformed/bounds edge cases (length past buffer, varint
   straddling the frame end, zero-length payload, non-minimal varint type).
3. The generated `peekFrameSize` reports `typeWidth + lengthWidth + length`
   (matching the hand-written one) so the streaming frame reader is unchanged.
4. No `length`/`frameType` value leaks to call sites beyond a defaulted
   discriminator field.

Until then, socket keeps `Http3FrameCodec` hand-written (it is correct and
interop-proven) and migrates only the clean bodies (`Http3Setting` done).

## Pointers

- socket hand-written codec (the round-trip oracle):
  `socket-http3/src/commonMain/.../Http3FrameCodec.kt` (283 lines) and its model
  `Http3Frame.kt`.
- socket varint codec to delegate to:
  `socket-http3/src/commonMain/.../VarIntCodec.kt` (`Codec<Long>`, RFC 9000 §16).
- buffer reference fixture (opaque + stored-length, no Unknown):
  `buffer-codec-test/.../protocols/http3/{Http3Frame,Http3LengthCodec}.kt`.
- validators to touch: `ProtocolMessageProcessor.kt` `validateForwardCompatible`
  (F2, ~1910) and `validateFramedBy` (E3, ~1793); dispatch/`else`-arm emission in
  `CodecEmitter.kt` (`Discriminator.Varint`, ~1948) and forward-compat capture
  (~7777).
