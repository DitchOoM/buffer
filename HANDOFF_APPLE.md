# Handoff — buffer-crypto 6.0: Apple-target verification (Mac only)

**Read this top to bottom, then do the task.** This is the *only* unverified surface left in
the buffer-crypto 6.0 reshape. Everything else is committed and green.

## State

- Branch: `crypto/v6-witness-hardware-ready` (5 commits ahead of `main`):
  - `67dde588` — AEAD capability witnesses + sealed keys (part 1)
  - `b0a94885` — Signature capability witnesses + sealed keys (part 2)
  - `cc5fbe02` — KeyAgreement capability witnesses + sealed keys (part 3)
  - `ce7078cd` — HPKE witness + finish nullables + retire AEAD flags (part 4)
  - `489bb255` — hardware-ready key witness + provider SPI + ABI lock (part 5)
- **Verified green already** (on a Linux/WSL host): JVM `174/0/0`, jsNode + wasmJsNode
  `174/0/0`, linuxX64 main+test compile, **Android instrumented `174/174` on real ART**,
  ktlint, `apiCheck`, and `/security-review` (clean).

## Why this machine

This Linux/WSL host **cannot register Apple targets** — they are gated in
`buffer-crypto/build.gradle.kts` behind `isRunningOnGithub && HostManager.hostIsMac`. So across
**all five slices**, the Apple `*.apple.kt` actuals were written to the committed AEAD pattern
but have **never been compiled or run**. On a Mac dev host the build registers `macosArm64`
(Apple Silicon) or `macosX64` (Intel). Building also compiles the Swift `CryptoKitShim`
(`xcrun swiftc -emit-library`) and the `commoncryptogcm` + `cryptokitshim` cinterops, so
**Xcode or the Command Line Tools must be installed**, plus **JDK 21**.

## Task

1. Build Apple main + test for the host arch and fix any compile errors **only** in the
   `appleMain` actuals: `Aead.apple.kt`, `AeadBridge.apple.kt`, `Signatures.apple.kt`,
   `KeyAgreement.apple.kt`, `Hpke.apple.kt`.
2. Run the Apple test suite to green (KAT / Wycheproof / tamper / capability + the new
   `HardwareKeyConformanceTest`).

**Do NOT touch common / JVM / JS / WASM / Linux / Android code — it is locked and verified.**
Only edit Apple actuals, and only to fix a real compile/test failure.

## Verify loop (substitute `macosX64` if on an Intel Mac)

```
./gradlew :buffer-crypto:compileKotlinMacosArm64
./gradlew :buffer-crypto:compileTestKotlinMacosArm64
./gradlew :buffer-crypto:macosArm64Test
./gradlew :buffer-crypto:ktlintCheck :buffer-crypto:apiCheck
```

`apiCheck` is intentionally **JVM/Android `.api` only** (klib validation disabled in
`build.gradle.kts` via `apiValidation { klib { enabled = false } }`) — it is host-independent
and will behave identically here. **Do not enable klib validation**; it would diverge between a
partial-target dev host and all-target CI.

## Known Apple behaviours — confirm they hold; do NOT "fix" them away

- **CryptoKit Ed25519 is hedged (randomized)**, not deterministic, so the Ed25519 KAT verifies
  a known-good signature + round-trips its own rather than asserting exact bytes.
- **`supportsEcdsaSigningFromScalar` is `true` on Apple** via the CryptoKit shim (Security.framework
  alone cannot build a key from a bare scalar). Because of this the new hardware signing
  conformance tests (`hardwareSignVerifiesUnderPublicKey`, `hardwareSigningHasNoBlockingPath`,
  `hardwareSigningDeniedAuthorizationFails`) **do run** on Apple.
- **`ecdsaSignatureEncoding` on Apple is `Der`.**
- The part-5 hardware slice is **pure-common** (no `*.apple.kt` changes); its conformance test
  runs unchanged on Apple through the common path.

## Done criteria

`macosArm64Test` (or `macosX64Test`) green + ktlint + apiCheck clean. Report the per-suite test
counts and confirm the Apple actuals are verified. If Xcode/CLT is missing, say so — that is the
only hard blocker. **Do not open a PR unless asked.** Full per-slice detail is in the
`crypto-v6-witness-hardware` auto-memory and `~/.claude/plans/effervescent-gliding-hennessy.md`.

> Housekeeping: this file is a cross-machine handoff, not library content. Delete it
> (`git rm HANDOFF_APPLE.md`) before the PR merges.
