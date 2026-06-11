# Changelog

All notable changes to this project are documented here. This project adheres
to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Codec processor: `@FramedBy` after a varint discriminator** — the `after`
  framing header may now be a varint value class (inner scalar carrying
  `@UseCodec(<VariableLengthCodec>)`, e.g. an HTTP/3 frame type). The framed
  encode measures the header's width per value via the codec's `wireSize`
  (instead of a compile-time constant), the framed `peekFrameSize` measures it
  via the codec's own peek, and the peek budget for the framing prefix is the
  framing codec's declared `maxWireSize` (a QUIC varint length is 1–8 bytes;
  the fixed path's literal budget is unchanged). Together with
  `@ForwardCompatible` below this enables a fully **length-free** declarative
  HTTP/3 frame model: `Type (varint)` + computed `Length (varint)` + payload,
  with strict body consumption (RFC 9114 §7.1 H3_FRAME_ERROR semantics).
- **Codec processor: `@ForwardCompatible` over varint discriminators** — the
  skip-and-preserve contract (F2/F5) now accepts a varint `@DispatchOn`
  discriminator in addition to single-byte ones. The preserved opcode is the
  discriminator's full decoded value; the `@UnknownVariant` sink's opcode
  parameter must be the discriminator's inner type (`Long` / `ULong`) so the
  preserve→re-encode round trip is lossless (re-encoded through the
  discriminator's own codec in canonical minimal form). Covers RFC 9114 §9
  ignore-unknown (reserved/GREASE frame types) for HTTP/3-style unions; the
  same shape fits QUIC frame types, HTTP/3 stream types, and QPACK opcodes.
- **`ViewCodec<T>` — explicit zero-copy escape from the raw-bytes lockdown.**
  `@RemainingBytes @UseCodec(C) val: T` may now carry a **non-`Payload`** type
  (including `ReadBuffer` itself) when `C` implements the new
  `com.ditchoom.buffer.codec.ViewCodec` marker: decode returns a borrowed view
  whose lifetime is tied to the source buffer, encode must be non-consuming.
  Implementing `ViewCodec` *is* the documented ownership answer the lockdown's
  prohibition exists to demand; `Payload`-marked self-contained values remain
  the default for anything that outlives the source buffer.

### Changed

- **Generated varint value-class codecs report Exact `wireSize`.** A
  `@ProtocolMessage` value class whose single field is a variable-length
  `@UseCodec` scalar (a varint wrapper) now forwards `wireSize` to the user
  codec (`VariableLengthCodec.wireSize` is `Exact(encodedLength)` by
  construction) instead of collapsing to `BackPatch`. Wire format unchanged;
  required so `@FramedBy`-after-varint can size the framing header per value.
- The new dispatch-value contract note for varint unions: a `@DispatchValue`
  projection narrowing a `Long`/`ULong` varint to `Int` should clamp
  out-of-range values to a sentinel (e.g. `-1`) rather than truncate —
  truncation can alias a huge unknown type onto a known small `@PacketType`
  value. The `protocols/http3fc` fixture pins the guard.

### Fixed

- **`PlatformBuffer.use {}` now releases pooled buffers.** The cleanup was gated
  on `this is CloseableBuffer`, but pooled buffers (`PooledBuffer` /
  `TrackedSlice`, returned by `BufferPool.acquire`/`slice`) are not
  `CloseableBuffer` — their release is a refcount decrement performed by
  `freeNativeMemory()`. As a result `use {}` silently failed to return pooled
  buffers to their pool, contradicting its own "works on all buffers" contract
  and leaking pool capacity. `use {}` now always invokes `freeNativeMemory()`;
  this is a no-op on GC-managed buffers and non-owning slices, frees native
  memory on `CloseableBuffer`s, and returns pooled buffers to the pool. No wire
  format or allocation-path change.

## [5.5.0] — 2026-06-05

### Added

- **Codec processor: multi-byte `@DispatchOn` discriminators across every
  integer inner kind** — signed `Short` / `Int` / `Long` and unsigned `ULong`,
  in addition to the existing single-byte (`UByte` / `Byte`) and 2/4-byte
  unsigned (`UShort` / `UInt`) kinds. Decode and encode already handled these;
  the missing piece was the dispatcher's `peekFrameSize` byte-reconstruction,
  which now assembles the discriminator order-aware (honoring the discriminator
  value class's `@ProtocolMessage(wireOrder = …)`) in the `Int` domain for
  2/4-byte inners and the `Long` domain for 8-byte inners, narrowing
  sign-preservingly to the inner kind. **Wire format and all existing generated
  codecs are byte-for-byte unchanged.**

### Changed

- **Codec processor: a previously-silent malformed sealed-dispatch shape now
  fails the build with a diagnostic instead of silently generating no codec**
  (the "Outcome-3 silent gap" class): a `@PacketType` variant under a *simple*
  sealed-dispatch parent that is neither a `data class` nor an `object` /
  `data object`.

  Wire format and **all existing generated codecs are byte-for-byte unchanged**
  — only inputs that previously produced no codec are affected.

### Internal

- Unified the two sealed-dispatch codegen paths (simple `@PacketType` and
  `@DispatchOn`) into a single `DispatchShape` IR parameterized by a
  `Discriminator` sum type, with one shared emit builder set. Byte-identical
  refactor (305 codec-snapshot goldens unchanged); groundwork for a future
  variable-width (QUIC-varint / HTTP/3) discriminator.

## [5.0.x] — Silent breaking changes from 4.x not documented in the 5.0.0 notes

The 5.0.0 release went through a strip-and-rebuild of `buffer-codec-processor`.
A handful of v4 behaviors were silently lost in that rebuild and were not
called out in the breaking-changes list below. Documenting them here so users
upgrading from 4.x know what to look for.

- **SPI extension system removed (`buffer-codec-processor/.../spi/CodecFieldProvider`).**
  v4 exposed a `CodecFieldProvider` interface, discovered via ServiceLoader,
  that let external users plug custom field strategies into KSP code
  generation. The whole `spi/` package was deleted in the v5 rebuild and not
  replaced. Source-incompatible for any project that shipped a custom
  `CodecFieldProvider`. **No drop-in replacement.** If you need custom field
  handling in v5, model it as a hand-written `Codec<T>` referenced via
  `@UseCodec` instead. Tracking restoration as a separate decision — file an
  issue if you depended on this.

- **`@Payload` annotation replaced by `Payload` interface.**
  - v4: `data class Packet<@Payload P>(...)` — type-parameter annotation
    from `com.ditchoom.buffer.codec.annotations`.
  - v5: `data class Packet<P : Payload>(...)` — marker interface bound from
    `com.ditchoom.buffer.codec`.
  - Migration: drop the `@Payload` annotation on the type parameter and add
    `: Payload` as the type bound; have your payload type extend
    `Payload`.

- **Nested-class codec naming convention regressed in 5.0.0, restored in 5.1.0
  (issue #156).** v4 generated codec object names with the enclosing-type
  chain flattened: `MqttPacket.SubAck` → `MqttPacketSubAckCodec`. v5.0.0
  silently dropped the flattening and generated `SubAckCodec`, which made
  any two same-named nested classes in the same package collide at KSP file
  write time with `kotlin.io.FileAlreadyExistsException`. **5.1.0 restores
  the v4 always-flatten convention** — if you upgraded a v4 project to v5.0.0
  and updated references to simple names, you will need to flatten them
  again when moving to 5.1.0. Wire format is unchanged.

- **Batching read/write coalescing removed (perf regression, not source-breaking).**
  v4's `BatchOptimizer` fused adjacent fixed-width scalar reads/writes into
  single bulk operations (e.g. four `readUByte()` → one `readInt()` + shift
  decompose). The optimizer was deleted in the v5 rebuild. Generated code
  still works correctly, just emits one read/write per scalar field — a
  measurable throughput regression on hot decode paths. Restoration is in
  flight; track via the perf branch.

- **Test coverage gap from the rebuild.** 55 v4 test/fixture files were
  deleted in the v5 strip. Some were renamed (e.g. v4's
  `MqttPacketRoundTripTest` is covered today by `MqttPacketCodecTest` +
  per-variant `MqttConnectCodecTest`, `MqttSubAckCodecTest`, etc.); others
  are not re-tested anywhere. The codec naming regression above only
  surfaced because an external user (issue #156) hit it in production —
  v4 had `AnimChunkRoundTripTest.kt` guarding that behavior, and it was
  deleted in the rebuild. We're auditing the full deletion list to identify
  v4 behaviors that are no longer regression-tested in v5; results will be
  filed as follow-up issues.

## [5.0.0]

This release reworks the `buffer-codec` framing API and unifies the buffer
bounds-checking / exception model across platforms. The codec changes are
**source-breaking** for code that consumed `buffer-codec` 4.2.x — see the
migration notes below.

### Breaking changes

- **`Codec<T>` split into `Encoder<in T>` / `Decoder<out T>` / `FrameDetector`.**
  `Codec<T>` still extends all three, so a type that declares `Codec<T>` and
  implements `encode` / `decode` keeps compiling. Send-only or receive-only
  consumers can now depend on just `Encoder<T>` or `Decoder<T>`.

- **`Encoder.sizeOf` removed — replaced by `Encoder.wireSize`.**
  - Before: `fun sizeOf(value: T): SizeEstimate`
  - After: `fun wireSize(value: T, context: EncodeContext): WireSize`
  - The `SizeEstimate` sealed type (`Exact` / `UnableToPrecalculate`) is removed.
    The replacement `WireSize` has `Exact(bytes)` and `BackPatch` (the framework
    encodes into a `GrowableWriteBuffer` and back-patches length prefixes).
  - Migration: implement `wireSize`; return `WireSize.Exact(n)` for
    fixed-size codecs, `WireSize.BackPatch` otherwise.

- **`PeekResult` moved package and changed shape.**
  - Package: `com.ditchoom.buffer.stream` → `com.ditchoom.buffer.codec`.
  - Variants: now `Complete(bytes)` / `NeedsMoreData` / `NoFraming`. The new
    `NoFraming` default makes a codec at a streaming boundary fail loudly at
    startup instead of silently hanging the receive loop.
  - Migration: update the import; replace any `PeekResult.Size(n)` with
    `PeekResult.Complete(n)`.

- **`CodecContext.Key<T>` split into directional keys.**
  - The single nested `CodecContext.Key<T>` is replaced by `DecodeKey<T>`,
    `EncodeKey<T>`, and `CodecKey<T>` (the last extends both).
  - `DecodeContext.with` / `EncodeContext.with` now take the matching
    directional key type.
  - Migration: declare context keys as `object`s implementing the correct
    direction marker.

- **`BufferOverflowException` / `BufferUnderflowException` are now common types.**
  Both are `expect class … : RuntimeException`. On JVM/Android the actuals
  subclass `java.nio.Buffer{Overflow,Underflow}Exception`, so existing
  `catch (e: java.nio.BufferOverflowException)` still matches. Non-JVM targets
  previously threw platform-native exceptions (or, on Apple/Linux native,
  segfaulted on overflow) — they now throw the common type. Code in
  `commonMain` can now `catch (e: BufferOverflowException)` portably.

- **`@WhenTrue` annotation renamed to `@When`.** Semantics unchanged.

- **`@WireOrder` now overrides the buffer's `byteOrder` (behavioral change, issue #154).**
  A field annotated `@WireOrder(Endianness.Little)` is encoded/decoded
  little-endian regardless of the `ReadBuffer`/`WriteBuffer` byte order.
  Previously the buffer's `byteOrder` could leak through for some scalar
  shapes. Code that compensated for the old bug by flipping `byteOrder`
  globally will now double-correct — remove the workaround.

- **`buffer-flow` unused bridge API removed.** `Connection.asByteStream`,
  `ByteStream.asCodecConnection`, `ByteStream.asFramedCodecConnection`,
  `Sender.contramap`, `Receiver.map`, `Receiver.mapNotNull`, and
  `Connection.map` are removed. `Connection.mapNotNull(encode, decode)` —
  the dual-direction mapper used for protocol layering — is retained.

- **`DecodeContext.getOrDefault` / `EncodeContext.getOrDefault` removed.**
  Use `context[key] ?: default` at the call site.

### Added

- `GrowableWriteBuffer` — auto-growing write buffer backing the `WireSize.BackPatch`
  encode path.
- Directional `@UseCodec` — accepts an `Encoder<T>`, `Decoder<T>`, or `Codec<T>`;
  direction is inferred or set via `@ProtocolMessage(direction = …)`.
- `@When` (conditional fields) and `@RemainingBytes` (limit-bounded trailer).
- `CodecContext` — typed `EncodeContext` / `DecodeContext` forwarded through
  generated code (sealed dispatch, `@UseCodec`, nested `@ProtocolMessage`).
- Cross-platform bounds-check parity: every platform buffer routes relative and
  absolute primitive reads/writes through a shared `BoundsChecks` helper, with
  `BufferOverflowTests` / `BufferUnderflowTests` pinning the contract.

### Fixed

- `@WireOrder` / `byteOrder` mismatch on generated codecs for every scalar shape
  including `Float` / `Double` (issue #154).
- Apple / Linux native buffers no longer segfault the process on write overflow —
  the unsafe pointer writes are now bounds-checked.
- `buffer-compression` (JS / WasmJs): persistent zlib streams preserve the LZ77
  window across flushes; negative `windowBits` normalized for Node.js;
  Node v24+ `Z_STREAM_END` assertion avoided.

[5.0.0]: https://github.com/DitchOoM/buffer/releases/tag/v5.0.0
