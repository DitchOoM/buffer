# Secure Enclave test harness

An on-device guard for the **Secure Enclave** path of `buffer-crypto`'s CryptoKit shim
(`bcks_secure_enclave_*` in `src/nativeInterop/swift/CryptoKitShim.swift`), which the
Kotlin/Native `appleMain` provider (`HardwareKeys.apple.kt`) binds.

GitHub-hosted CI cannot exercise a real secure element, so the Kotlin `HardwareKeyConformanceTest`
treats Apple as `Unavailable` there. This SwiftPM package fills that gap: its test
**symlinks the real shipped shim** (`Sources/CryptoKitShim/CryptoKitShim.swift` →
`../../../src/nativeInterop/swift/CryptoKitShim.swift`), so it always exercises the exact
`@_cdecl` code the library ships — it cannot drift from a copy — and drives a full
generate → sign → verify round-trip against a genuine Secure Enclave.

## Run it

```bash
cd buffer-crypto/secure-enclave-harness

# macOS host — runs against the Mac's REAL hardware Secure Enclave on Apple Silicon.
# (On a Mac without an entitled bundle / without an Enclave, the tests XCTSkip rather than fail.)
swift test
```

A passing `swift test` on an Apple-Silicon Mac is real secure-element validation: the Mac's
Secure Enclave mints the P-256 key, signs inside the element, and CryptoKit verifies the DER
signature against the returned public point. The iPhone runs this identical CryptoKit code.

## Why not the iPhone directly?

A SwiftPM *library* test target is "tool-hosted" and **cannot run on a physical iOS device**
(`xcodebuild` reports "Tool-hosted testing is unavailable on device destinations — select a host
application"). To run on a connected iPhone you'd add a minimal iOS **host app** target and host
the tests in it. That's deliberately out of scope here: the macOS run already exercises the same
CryptoKit `SecureEnclave` code against real Apple-silicon hardware. The simulator has no Secure
Enclave (`SecureEnclave.isAvailable == false`), so a simulator run only proves the skip path.

## What it caught

The first run found a real bug: a Secure Enclave P-256 signing key's `dataRepresentation` is
~**284 bytes**, but the Kotlin provider's output buffer was capped at 256 — so `generate` returned
`BCKS_ERR_BUFFER` and the Apple provider would have silently failed to resolve on every device.
The cap is now 1024 with margin, asserted here (`blobLen < blobCap`).
