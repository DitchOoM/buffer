# Changelog

All notable changes to this project are documented here. This project adheres
to [Semantic Versioning](https://semver.org/).

## [4.3.0]

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

[4.3.0]: https://github.com/DitchOoM/buffer/releases/tag/v4.3.0
