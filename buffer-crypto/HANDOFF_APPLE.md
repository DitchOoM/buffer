# Apple actuals — handoff for macOS completion

**Status:** PR #204 (`crypto/standalone-main` → `main`) is green on **JVM, JS, WASM, Android**.
The **Apple** compile fails — the new families' Apple actuals were written to the
CommonCrypto/Security pattern on a Linux host (no Apple toolchain) and Mac CI surfaced the
real binding errors. This doc is the self-contained brief to finish them on a Mac.

The foundation Apple actuals (SHA-256/HMAC: `Sha256Digest.apple.kt`, `HmacSha256Mac.apple.kt`,
`CryptoRandom.apple.kt`) **do compile and pass** — use them as the known-good reference idiom.

## Build / test on a Mac

```bash
# Compile-only (fast iteration):
./gradlew :buffer-crypto:compileKotlinMacosArm64
# Full Apple test suite (KAT + Wycheproof + negative + capability + backing, all in commonTest):
./gradlew :buffer-crypto:macosArm64Test :buffer-crypto:iosSimulatorArm64Test
./gradlew :buffer-crypto:ktlintCheck
```

CI job to watch: `build-apple / Apple Targets` on the PR.

## Definition of done

1. `compileKotlinMacosArm64` (+ ios/tvos/watchos targets) clean.
2. `macosArm64Test` + `iosSimulatorArm64Test` green — the shared `commonTest` suite proves
   correctness on Apple the same way it does on JVM/JS/WASM.
3. `ktlintCheck` clean.
4. **Capability flags reflect *real* Apple support.** Native-or-throw: if a primitive genuinely
   can't be done with CommonCrypto/Security (CryptoKit-only — see §"Stays gated"), leave it
   gated (`supports… = false` → throws `UnsupportedOperationException`); do **not** stub it.
5. Naming guardrail: name everything by algorithm; no protocol-suite names anywhere.

## The errors, by file (with root cause + fix direction)

### `Aead.apple.kt` + `AeadBridge.apple.kt` — AES-GCM (the substantive one)

```
Aead.apple.kt:14/15  Unresolved reference 'CCCryptorGCMOneshotDecrypt' / '...Encrypt'
Aead.apple.kt:63,111 (same)
Aead.apple.kt:65-70,113-118  Cannot infer type for type parameter 'R'   <- cascade from above
Aead.apple.kt:79 / AeadBridge.apple.kt:75  'MatchGroup?' but 'Byte' expected  <- cascade (`tag[i]`)
```

**Root cause:** `CCCryptorGCMOneshotEncrypt` / `CCCryptorGCMOneshotDecrypt` are **not** in the
Kotlin/Native `platform.CoreCrypto` bindings (they live in `CommonCryptorSPI.h`, outside the
public module map). Every other error in this file cascades from these two unresolved symbols —
fix the GCM call and the type-inference / `tag[i]` errors disappear.

**Fix — pick one, verify the symbol names against the actual `platform.CoreCrypto` klib:**
- **(preferred, no shim)** Use the bound streaming GCM API:
  `CCCryptorCreateWithMode(kCCEncrypt|kCCDecrypt, kCCModeGCM, kCCAlgorithmAES, ccNoPadding,
  iv=null, key, keyLen, …)` → `CCCryptorGCMAddIV` → `CCCryptorGCMaddAAD` →
  `CCCryptorGCMEncrypt`/`CCCryptorGCMDecrypt` → `CCCryptorGCMFinal` (writes the 16-byte tag) →
  `CCCryptorRelease`. Confirm `kCCModeGCM` and the `CCCryptorGCM*` functions are present in the
  binding (autocomplete on `platform.CoreCrypto.`). Keep the existing security contract: on
  **decrypt**, compare the recomputed tag to the supplied tag with `constantTimeEquals`, scrub
  the plaintext dest, and throw `VerificationFailed` on mismatch **before** returning — no
  unverified-plaintext release.
- **(alternative)** Route AES-GCM through CryptoKit `AES.GCM` like ChaCha already does via the
  bridge — only if you're adding the CryptoKit shim anyway (see #201). Avoids the streaming
  state machine but needs the Swift interop.

The decrypt path's tag-verify + scrub logic is already correct in the JVM/Apple-manual design
(reviewed) — preserve it; only the GCM primitive call changes.

### `Signatures.apple.kt` — ECDSA (CF interop idioms)

```
Signatures.apple.kt:12,136  Unresolved reference 'CFBridgingRelease'
Signatures.apple.kt:93,96   actual type 'Any', but 'NSCopyingProtocol' expected   (dict.setObject(...))
Signatures.apple.kt:99      CFBridgingRetain
Signatures.apple.kt:134,158 CPointer<...> vs CPointer<__CFString>? mismatch
```

**Root cause:** uses ObjC-ARC bridging (`CFBridgingRetain`/`CFBridgingRelease`) + an
`NSMutableDictionary.setObject(... as Any)` to build the SecKey attribute dictionary — neither
plays well with Kotlin/Native CF interop here.

**Fix:** build the attribute dictionary the way `KeyAgreement.apple.kt::attributes()` already
does and is **known-good** — `CFDictionaryCreateMutable` + `CFDictionarySetValue(dict,
kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)` etc., and release with `CFRelease`. Drop the
`NSMutableDictionary` / `CFBridging*` path entirely. For the `134/158` mismatches, those are the
`SecKeyCreateWithData` / `SecKeyCopyExternalRepresentation` error-pointer args — pass a proper
`CFErrorRef` var (or `null`) of the expected pointer type. Copy the exact call shape from
`KeyAgreement.apple.kt`, which compiles.

### `KeyAgreement.apple.kt` — two small ones

```
KeyAgreement.apple.kt:140  Unresolved reference 'Default'   (BufferFactory.Default)
KeyAgreement.apple.kt:97   receiver type mismatch on CFDataGetBytePtr()[i]
```

**Fix:** add `import com.ditchoom.buffer.Default` (present in `Signatures.apple.kt:6`, missing
here). For `:97`, `CFDataGetBytePtr` returns a `CPointer<UByteVar>?` — index then convert, e.g.
`out.writeByte(src!![i].toByte())` with the pointer typed as unsigned, or `reinterpret()` to
`ByteVar` first; confirm against the compiler.

## Stays gated — do NOT implement here (tracked in #201)

These are CryptoKit-only (Swift), not reachable from CommonCrypto/Security cinterop. They
correctly report `false` + throw today; leaving them gated is the right answer until the
CryptoKit shim lands:
- ChaCha20-Poly1305 (`appleChaChaPolyAvailable = false`)
- Ed25519 signatures
- X25519 key agreement
- ECDSA signing **from a bare scalar** (`supportsEcdsaSigningFromScalar = false`) —
  `SecKeyCreateWithData` can't derive the point; verify + sign-from-full-key work.

So the Apple deliverable for this PR is: **AES-GCM, ECDSA (verify + full-key sign), ECDH, and
the full SHA-2/HMAC/HKDF family** compiling and passing the common suite. Everything else stays
honestly gated.

## Reference: idioms that already compile

- CommonCrypto digest/HMAC: `Sha256Digest.apple.kt`, `HmacSha256Mac.apple.kt`
- CSPRNG via Security: `CryptoRandom.apple.kt`
- **CF dictionary + SecKey, the correct idiom:** `KeyAgreement.apple.kt::attributes()`,
  `generateKeyPair()` (uses `CFDictionaryCreateMutable`/`CFDictionarySetValue`/`CFRelease`,
  `SecKeyCreateRandomKey`, `SecKeyCopyExternalRepresentation`).

See `SECURITY.md` for the full testing strategy and the attack-vector coverage each Apple
primitive must satisfy once enabled.
