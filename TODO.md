# buffer — open follow-ups

Tracked here so known gaps aren't lost. Remove or update entries as they're resolved.

## buffer-compression — Android instrumented coverage for the persistent zlib matrix

- [ ] Run `:buffer:connectedCheck` (Android instrumented) so the Android compressor
  implementation is covered by `StreamingCompressorApiMatrix` +
  `CompressionLifecycleContractTests` (currently exercised on JVM/JS/Apple/Linux).

## buffer-compression — `supportsStatefulFlush` on `SuspendingStreamingCompressor`

- [ ] `supportsStatefulFlush` is documented per-platform on the *sync*
  `StreamingCompressor`, but the async `SuspendingStreamingCompressor` (Node
  Transform stream, Apple async impl, browser `CompressionStream`) has its own
  state-takeover semantics not reflected in any flag. If a downstream cares, add a
  `supportsSuspendingStatefulFlush` or expand the existing flag's contract.

## buffer-compression — `windowBits` on `SuspendingStreamingCompressor`

The sync `StreamingCompressor.create` now takes a `windowBits: WindowBits` parameter
(Linux/Apple/JS-Node thread it through), but the async
`SuspendingStreamingCompressor.Companion.create` still has none.

- [ ] Add `windowBits` to `SuspendingStreamingCompressor.create` (expect + actuals).
- [ ] Wire it through `NodeTransformStreamingCompressor`.
- [ ] Document JVM's `java.util.zip.Deflater` gap — Netty's jzlib hybrid is the
  canonical revisit path.
