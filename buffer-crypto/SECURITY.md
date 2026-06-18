# `:buffer-crypto` — security model, testing strategy & coverage

This document is the standing security reference for the `:buffer-crypto` module: what it
guarantees, how every primitive is tested, which attack vectors are explicitly defended (and
how), what is intentionally unsupported, and how to report a vulnerability. It is updated as
part of every change to the module — a new primitive or platform is not "done" until its row
exists in the coverage and platform tables below.

## 1. Security model — what we guarantee vs. what we delegate

The guarantee stack, strongest first:

1. **Native-or-throw.** No primitive is implemented by hand. Each algorithm wraps the
   platform's vetted native stack — JCA/Conscrypt (JVM/Android), CommonCrypto + Security
   framework (Apple), WebCrypto (JS/WASM). If a platform cannot provide an algorithm, the call
   throws `UnsupportedOperationException` and the matching `supports…` capability flag is
   `false`. **One documented exception:** a pure-Kotlin SHA-2 / HMAC core on JS/WASM, because
   WebCrypto's `digest` is async-only and cannot satisfy the synchronous contract. That core is
   pinned by NIST/RFC known-answer vectors including padding-boundary cases.
2. **Cross-platform correctness** is proven by **known-answer vectors (RFC/NIST) + Wycheproof**
   (Google/C2SP) run on *every* target. The same vectors passing everywhere gives transitive
   cross-platform consistency — a tag/signature produced on one platform verifies on another.
3. **Negative / tamper tests** prove that failures *throw* and never silently pass. This
   exercises our own verification branch, which a known-answer test never does.
4. **Misuse resistance** comes from typed keys, CSPRNG-only nonces by default, and runtime
   public-key validation.

**What we explicitly do NOT guarantee** (documented trust boundary): side-channel / timing /
fault resistance of the underlying native primitive, and the constant-time execution of the
platform's own verify. We trust the platform for these. Our *own* secret-dependent comparisons
are constant-time (see §3); the primitive's internal timing is the platform's responsibility.

### Exception contract

- `VerificationFailed` — **opaque and uniform.** No reason, no cause. Every AEAD-tag /
  signature / authenticated-decrypt failure collapses to this. Granular failure reasons on a
  verify path are an oracle, so we do not expose them.
- `CryptoMisuseException` (sealed) — **structured, non-secret detail**, e.g.
  `InvalidPublicKey(curve)` for a low-order / off-curve / identity point or the X25519 all-zero
  shared-secret rejection.
- Unsupported algorithm/platform → `UnsupportedOperationException` (capability contract).
- Input precondition violations (destination too small, wrong nonce/key length) →
  `IllegalArgumentException`, thrown in common code before dispatch, so it is byte-identical on
  every platform.

**Invariant:** no secret material (private keys, plaintext, shared secrets, derived material)
ever appears in an exception message, property, cause, or log.

## 2. Testing strategy

Every primitive is covered by five independent test classes, all in `commonTest` so they run on
**every** target (JVM, Android, Apple, JS, WASM):

| Class | What it proves |
|---|---|
| **Known-answer (KAT)** | RFC/NIST published vectors — bit-exact correctness of the happy path. |
| **Wycheproof** | Curated real Google/C2SP vectors: `valid`→must succeed, `invalid`→must reject, `acceptable`→pinned per-platform decision (a behavior change becomes a regression). |
| **Negative / tamper** | Every single-byte flip of tag/ciphertext/AAD/signature throws `VerificationFailed`; truncated/oversized inputs and wrong keys reject. |
| **Capability** | Where a flag is `true` the op works; where `false` it throws `UnsupportedOperationException`. The unsupported contract is tested, not assumed. |
| **Backing matrix** | Every op runs through heap × direct × pooled × slice buffer backings for input/AAD/dest, asserting identical output — covers both platform bridge branches and the wrapper-transparency contract. |

Test inputs are built with buffer-typed helpers (`hexBuffer(...)`) — there are no raw
`ByteArray`/primitive-array inputs anywhere in the suite.

Run the full suite:

```bash
./gradlew :buffer-crypto:jvmTest :buffer-crypto:jsNodeTest :buffer-crypto:wasmJsNodeTest :buffer-crypto:ktlintCheck
# Apple (macOS host / CI):
./gradlew :buffer-crypto:macosArm64Test :buffer-crypto:iosSimulatorArm64Test
# Android instrumented (API 28+ device/emulator):
./gradlew :buffer-crypto:connectedDebugAndroidTest
```

### Test inventory (this release)

- Hashes: `Sha256Test`, `Sha384Sha512Test`, `HmacSha256Test`, `HmacSha384Sha512Test`,
  `HkdfTest`, `HkdfSha384Sha512Test`, `Sha512FamilyWycheproofTest`
- AEAD: `AeadTest`, `AeadWycheproofTest`, `AeadTamperTest`
- Signatures: `SignatureKatTest`, `SignatureWycheproofTest`, `SignatureTamperTest`,
  `SignatureCapabilityTest`, `SignatureBackingTest`
- Key agreement: `KeyAgreementKatTest`, `KeyAgreementWycheproofTest`, `KeyAgreementTest`
- Foundation: `ConstantTimeTest`, `CryptoRandomTest`, `SecureBufferTest`,
  `WycheproofRunnerSelfTest`
- Vendored Wycheproof vector sets (curated, in `WycheproofVectors*.kt`): AES-GCM,
  ChaCha20-Poly1305, ECDSA P-256/384/521 (DER **and** P1363), Ed25519, ECDH P-256/384/521
  (SPKI **and** raw-point WebCrypto), X25519, HMAC-SHA-512, HKDF-SHA-512.

## 3. Attack-vector coverage

Defense classes: **(a)** KAT/Wycheproof vector · **(b)** runtime check in our wrapper glue ·
**(c)** delegated to the native primitive **and asserted** by a negative test · **(d)**
prevented by API/type design · **(e)** out of scope / documented trust assumption.

### AEAD (AES-GCM 128/256, ChaCha20-Poly1305)
| Vector | Defense |
|---|---|
| Nonce reuse | **(d)** CSPRNG-only nonce is the default public API; explicit-nonce primitives are `internal` |
| Nonce length ≠ 96 bits | **(d)** enforce 12-byte IV + **(a)** + negative test |
| Tag truncation (< 128-bit) | **(d)** fixed 16-byte tag + **(a)** + negative test |
| Tag / ciphertext / AAD bit-flip | **(a)** + **(c)** decrypt throws `VerificationFailed` |
| Plaintext released before tag verified | **(b)** one-shot; on the Apple manual path the dest is constant-time-compared and scrubbed before throw; **(e)** native streaming semantics |
| Key-size confusion (128 vs 256) | **(d)** typed sized keys + **(a)** |
| ChaCha on WebCrypto | **(b)/(d)** throws — never polyfilled |
| AES/GHASH timing side-channel | **(e)** trust AES-NI/CLMUL |

### Signatures (ECDSA P-256/384/521, Ed25519)
| Vector | Defense |
|---|---|
| Non-canonical DER (leading zeros, long-form length, trailing garbage) | **(a)** + **(b)** strict canonical-DER parse on the JVM verify path |
| `r=0` / `s=0` / `r,s ≥ n` | **(a)** + **(b)** explicit `0<r,s<n` range check |
| ECDSA malleability (low-S) | **(a)** + **(b)** where uniqueness is required (documented) |
| P1363 (raw r‖s) vs ASN.1 confusion | **(a)** + **(d)** per-platform encoding pinned |
| Point not on curve / infinity as pubkey | **(a)** + **(c)** |
| Curve ↔ hash mismatch | **(d)** binding is in the typed scheme (P-256+SHA-256, P-384+SHA-384, P-521+SHA-512) |
| Ed25519 non-canonical S / small-order A or R | **(a)** + **(c)** |
| Ed25519 vs Ed25519ph/ctx confusion | **(d)** pure Ed25519 pinned |
| `k` reuse / biased nonce | **(c)/(e)** native RFC-6979 / CSPRNG |

### Key agreement (X25519, ECDH P-256/384/521)
| Vector | Defense |
|---|---|
| X25519 all-zero shared secret | **(b)** constant-time all-zero rejection → `InvalidPublicKey` + **(a)** |
| Low-order / small-subgroup public point | **(a)** + **(b)**/**(c)** |
| ECDH invalid-curve / off-curve / infinity point | **(a)** + **(c)** provider rejects; checked & unchecked provider exceptions map uniformly to `InvalidPublicKey` (no exception-type oracle) |
| Raw shared secret used without a KDF | **(b)/(d)** the API forces HKDF over the shared secret; the raw DH output is never returned |
| Compressed/SPKI vs raw-point confusion | **(a)** + **(d)** per-platform encoding pinned |
| Scalar-mult timing | **(e)** |

### KDF / MAC (HKDF, HMAC-SHA-2)
| Vector | Defense |
|---|---|
| Skipping extract (IKM as PRK) | **(a)** + **(d)** extract-then-expand enforced |
| `L > 255·HashLen` / `L = 0` edge | **(a)** + **(b)** length cap |
| Non-constant-time tag compare in glue | **(b)** `constantTimeEquals`; **(e)** native verify |
| Hash-length confusion (256/384/512) | **(d)** typed |

## 4. Platform support & capability gating

These are tested error paths, not assumptions — the capability flag drives a `true`→works /
`false`→throws test on every target.

| Primitive | JVM | Android (minSdk 28) | Apple | JS / WASM |
|---|---|---|---|---|
| SHA-256/384/512, HMAC, HKDF | ✓ | ✓ | ✓ | ✓ (pure-Kotlin core) |
| AES-GCM 128/256 | ✓ | ✓ | ✓ | ✓ (WebCrypto, async) |
| ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ⚠ pending (see §5) | ✗ throws — not in WebCrypto |
| ECDSA P-256/384/521 (verify) | ✓ | ✓ | ✓ | ✓ |
| ECDSA signing | ✓ | ✓ | ⚠ from-scalar gated (see §5) | ✓ |
| Ed25519 | ✓ (JDK 15+) | ✓ **API 34+** (runtime gate, throws 28–33) | ⚠ pending (see §5) | ✓ newer engines (feature-detected) |
| X25519 | ✓ (JDK 11+) | ✓ **API 34+** (runtime gate) | ⚠ pending (see §5) | ✓ newer engines (feature-detected) |
| ECDH P-256/384/521 | ✓ | ✓ | ✓ | ✓ |

Encoding differs by platform and is pinned + tested both ways: JCA uses DER/ASN.1 (ECDSA) and
SPKI/X.509 (ECDH); WebCrypto uses raw P1363 `r‖s` and raw points.

## 5. Known limitations & tracked follow-ups

These are **not vulnerabilities** — every case below is gated by a capability flag that is
`false` and throws `UnsupportedOperationException`, so there is no silent weakening. Each is
tracked to completion:

- **Apple ChaCha20-Poly1305, Ed25519, X25519** — these live in CryptoKit (Swift), which this
  module's Kotlin/Native cinterop does not yet reach (only CommonCrypto + Security framework are
  wired). They report unsupported and throw; a CryptoKit interop shim is the follow-up. Apple
  AES-GCM, ECDSA (verify; sign from a full key rep), and ECDH are complete.
- **Apple ECDSA signing from a bare scalar** — `SecKeyCreateWithData` cannot derive the public
  point from a private scalar alone, so `supportsEcdsaSigningFromScalar` is `false` on Apple;
  signing from a full key representation works, and verify is fully supported.
- **Android X25519/Ed25519 below API 34** — Conscrypt added these in Android 14; on API 28–33
  the capability flag is `false` and the call throws. This is a runtime (`SDK_INT`) gate, not a
  compile-time one.
- **Linux** — no native crypto target is registered yet; deferred. When added it will wrap
  BoringSSL (not OpenSSL), consistent with the sibling networking module.

Open tracking issues are linked from the pull request that introduces this module.

## 6. Reporting a vulnerability

If you believe you have found a security issue in this module, **please do not open a public
issue.** Email the maintainer (see the repository's published contact) with a description and,
if possible, a reproducing buffer/vector. There are no known exploitable vulnerabilities in this
module at the time of writing.

**How a fix flows:** because we wrap native primitives, most algorithmic fixes are platform
updates outside our control; our responsibility is the wrapper glue (encoding, validation,
constant-time compares, capability gating) and the test surface. A confirmed issue is fixed in
the glue and **locked in by a new Wycheproof/negative vector** so it cannot regress, then shipped
as a patch release. Every change to this module must pass the five test classes in §2 and a
`/security-review` pass before merge.

## 7. Dependency posture

Runtime: `kotlinx-coroutines-core` (the `*Async` wrappers await WebCrypto promises). Test-only:
`kotlinx-serialization-json` (parses vendored Wycheproof vectors; never shipped) and
`kotlinx-coroutines-test`. No third-party cryptographic dependency is bundled — every primitive
is the platform's own. No known-vulnerable dependency versions are in use.
