# Changelog

All notable changes to this project are documented here. This project adheres
to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- **Codec processor: two previously-silent malformed sealed-dispatch shapes
  now fail the build with a diagnostic instead of silently generating no
  codec** (the "Outcome-3 silent gap" class):
  - A `@PacketType` variant under a *simple* sealed-dispatch parent that is
    neither a `data class` nor an `object` / `data object`.
  - A `@DispatchOn` discriminator whose value-class inner scalar is not a
    supported dispatch width. Only single-byte (`UByte` / `Byte`) and 2/4-byte
    unsigned (`UShort` / `UInt`) kinds are supported; `Short`, `Int`, `Long`,
    and `ULong` discriminators now error rather than emitting nothing.

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
