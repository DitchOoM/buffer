# buffer — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.
Remove or update entries as they're resolved. Older entries first.

## buffer-compression — multiplatform coverage gaps for the JS persistent zlib changes

Today's commits (`8ced89ff`, `c31b130e`, `049b2a51`) reworked the JS / wasmJs persistent zlib path and added a parameterized API parity matrix in commonTest. The matrix runs against any KMP target you exercise, but the following weren't run on this Linux host today:

- [ ] Run `:buffer-compression:macosX64Test` (and `:macosArm64Test` / `:iosSimulatorArm64Test`) on a Mac host to confirm `StreamingCompressorApiMatrix` + `CompressionLifecycleContractTests` pass on the Apple zlib impl (`AppleStreamingCompression.kt`). The Apple path uses `deflateReset` / `inflateReset` on a native `z_stream` — same shape as Linux, but never validated by the new tests here.
- [ ] Run `:buffer-compression:jsBrowserTest` so the browser `CompressionStream` / `DecompressionStream` path is exercised by the new tests. Existing tests already gate on `supportsSyncCompression`, so the matrix should mostly skip — but the contract tests for `close()` / `reset()` semantics should still hit.
- [ ] Run `:buffer:connectedCheck` (Android instrumented) so the Android compressor implementation is covered by the new matrix.

## buffer-compression — `supportsStatefulFlush` on `SuspendingStreamingCompressor`

- [ ] `supportsStatefulFlush` is documented per-platform on the *sync* `StreamingCompressor`, but the async `SuspendingStreamingCompressor` (Node Transform stream path, Apple async impl, browser CompressionStream) has its own state-takeover semantics that aren't reflected in any flag. If a downstream cares (the websocket layer currently uses sync only, but mqtt could), add a `supportsSuspendingStatefulFlush` or expand the existing flag's contract.

## buffer-compression — windowBits on `SuspendingStreamingCompressor`

Tracked in [[buffer_compression_windowbits_followup]] — JVM/Android `supportsCustomWindowBits=false`. JS Node now threads `WindowBits` through the persistent factories (today), but `SuspendingStreamingCompressor.Companion.create` still has no `windowBits` parameter.

- [ ] Add `windowBits` to `SuspendingStreamingCompressor.create` (expect + actuals).
- [ ] Wire it through `NodeTransformStreamingCompressor`.
- [ ] Document JVM's `java.util.zip.Deflater` gap — Netty's jzlib hybrid is the canonical revisit path.
