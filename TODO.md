# buffer — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.
Remove or update entries as they're resolved. Older entries first.

## buffer-compression — multiplatform coverage gaps for the JS persistent zlib changes

Today's commits (`8ced89ff`, `c31b130e`, `049b2a51`, `4453b555`) reworked the JS / wasmJs persistent zlib path and added a parameterized API parity matrix in commonTest. The matrix runs against any KMP target you exercise.

- [x] **Apple targets**: PR #149's `build-apple` job ran the new `StreamingCompressorApiMatrix` + `CompressionLifecycleContractTests` against the Apple zlib impl (`AppleStreamingCompression.kt`) and went green.
- [ ] Run `:buffer-compression:jsBrowserTest` so the browser `CompressionStream` / `DecompressionStream` path is exercised by the new tests. Existing tests already gate on `supportsSyncCompression`, so the matrix should mostly skip — but the contract tests for `close()` / `reset()` semantics should still hit.
- [ ] Run `:buffer:connectedCheck` (Android instrumented) so the Android compressor implementation is covered by the new matrix.

## buffer-compression — `supportsStatefulFlush` on `SuspendingStreamingCompressor`

- [ ] `supportsStatefulFlush` is documented per-platform on the *sync* `StreamingCompressor`, but the async `SuspendingStreamingCompressor` (Node Transform stream path, Apple async impl, browser CompressionStream) has its own state-takeover semantics that aren't reflected in any flag. If a downstream cares (the websocket layer currently uses sync only, but mqtt could), add a `supportsSuspendingStatefulFlush` or expand the existing flag's contract.

## buffer-compression — windowBits on `SuspendingStreamingCompressor`

Tracked in [[buffer_compression_windowbits_followup]] — JVM/Android `supportsCustomWindowBits=false`. JS Node now threads `WindowBits` through the persistent factories (today), but `SuspendingStreamingCompressor.Companion.create` still has no `windowBits` parameter.

- [ ] Add `windowBits` to `SuspendingStreamingCompressor.create` (expect + actuals).
- [ ] Wire it through `NodeTransformStreamingCompressor`.
- [ ] Document JVM's `java.util.zip.Deflater` gap — Netty's jzlib hybrid is the canonical revisit path.
