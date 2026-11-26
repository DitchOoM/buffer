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
- Password-based KDF: `Pbkdf2Test` (RFC 7914 §11 PBKDF2-HMAC-SHA256 incl. c = 80 000, RFC 6070
  inputs over HMAC-SHA256/-SHA512, c = 210 000, multi-block partial output, non-UTF-8 and
  over-block-size passwords, backing matrix, parameter validation)
- AEAD: `AeadTest`, `AeadWycheproofTest`, `AeadTamperTest`
- Single-block AES (ECB): `AesEcbTest` (FIPS-197 Appendix C.1/C.3 + NIST SP 800-38A F.1
  single-block KAT, encrypt/decrypt round-trip, key/block-length validation, capability contract)
- Signatures: `SignatureKatTest`, `SignatureWycheproofTest`, `SignatureTamperTest`,
  `SignatureCapabilityTest`, `SignatureBackingTest`
- Key agreement: `KeyAgreementKatTest`, `KeyAgreementWycheproofTest`, `KeyAgreementTest`,
  `TlsPremasterSecretTest` (RFC 7748 §5.2 + NIST CAVP P-256 raw-secret KATs, premaster↔KDF
  consistency, low-order / off-curve rejection)
- HPKE / DHKEM (RFC 9180): `HpkeKatTest` (RFC 9180 Appendix A vectors, all four modes),
  `HpkeRoundTripTest`, `HpkeTamperTest`, `HpkeBackingTests`, `HpkeCapabilityTest`
- Non-exportable / hardware-backed keys & custody: `HardwareKeyConformanceTest` (SPI +
  gated-closure machinery, via a fake secure element), `KeyProviderResolverTest` (total
  per-algorithm routing + `requireTier`), `WebCryptoProtectedKeyProviderTest` (the js/wasmJs
  WebCrypto non-exportable provider end-to-end: ECDSA, AES-GCM, ECDH, HPKE `openBase`)
- EC public key from a private scalar: `EcPublicKeyFromScalarTest` (RFC 6979 A.2.5–A.2.7 key pairs,
  `d = 1` and `d = n − 1` for both Y parities, `0` / `n` / all-ones range rejection, width and
  curve rejection, scalar backing matrix)
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

### Single-block AES / ECB (raw block permutation)

This is a deliberately low-level primitive — the bare keyed AES permutation over one 16-byte block,
with **no** IV, chaining, padding, or authentication. It is **not** a general-purpose cipher and must
never be used for bulk confidentiality: equal plaintext blocks map to equal ciphertext blocks under
the same key, so ECB hides nothing about message content and detects no tampering. Its only intended
use is keystream/PRF constructions that run AES forward once — e.g. **DTLS 1.3 / TLS 1.3 record
sequence-number encryption** (RFC 9147 §4.2.3 / RFC 8446 §5.4.1), where the mask is
`AES-ECB-encrypt(sn_key, ciphertext_sample)` and both peers recover the sequence number by XORing
that mask (so only the forward direction is used). For confidentiality use the AEAD family above.

| Vector | Defense |
|---|---|
| Misuse as a bulk cipher | **(d)/(e)** single-block-only API (multi-block rejected) + documented "not for confidentiality" contract; ECB pattern-leakage is inherent and out of scope by design |
| Input length ≠ one 16-byte block | **(d)** enforce exactly 16 bytes + **(b)** runtime check + negative test |
| Key-size confusion (128 vs 256) | **(d)** typed sized key (`AesEcbKey`) + **(b)** length validation + negative test |
| Block permutation correctness | **(a)** FIPS-197 + NIST SP 800-38A single-block KAT on every native platform |
| Use on the web | **(b)/(d)** witness is `Unavailable` — no ECB in WebCrypto, never polyfilled (DTLS 1.3 does not run on the web) |
| AES timing side-channel | **(e)** trust AES-NI (JCA/CommonCrypto) / BoringSSL constant-time AES |

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
| Raw shared secret used without a KDF | **(b)/(d)** the default `deriveSharedSecret` forces HKDF over the shared secret and never returns the raw output; the sole raw path, `deriveTlsPremasterSecret`, is narrowly named for protocol key schedules, carries a standing "not a key" warning, and returns a wiped caller-owned `SecureBuffer` (see the premaster note below) |
| Compressed/SPKI vs raw-point confusion | **(a)** + **(d)** per-platform encoding pinned |
| Scalar-mult timing | **(e)** |

#### `deriveTlsPremasterSecret` — the one raw-secret path

`KeyAgreementAsyncOps.deriveTlsPremasterSecret(privateKey, peerPublicKey)` is the **only** public
API that returns the raw (EC)DHE shared secret (the internal `dhRawSecret` seam otherwise stays
`internal`, used only by HPKE/DHKEM). It exists so an out-of-module TLS/DTLS stack can obtain the
material its own key schedule requires — the TLS 1.2/DTLS 1.2 `premaster_secret`, or the TLS
1.3/DTLS 1.3 (EC)DHE input to `HKDF-Extract`. It is deliberately narrow, and misuse-sensitive:

| Vector | Defense |
|---|---|
| Raw DH output used directly as a key | **(d)** named for its single legitimate use (TLS/DTLS premaster / HKDF IKM) with a standing "this is NOT a key — run it through the protocol KDF" warning in the KDoc + this note; `deriveSharedSecret` remains the recommended default |
| All-zero / low-order / off-curve peer point | **(b)/(c)** identical validation to `deriveSharedSecret` — the shared `dhRawSecret` seam applies the constant-time X25519 all-zero rejection and the uniform provider `InvalidPublicKey` mapping *before* any secret is returned (`TlsPremasterSecretTest`) |
| Raw secret lingering in memory | **(d)** returned in a wiped `SecureBuffer` the caller owns and MUST free (`use {}` / `freeNativeMemory()`); never logged |
| Wrong-curve key confusion | **(a)** the ops are curve-bound; a private key off the ops' curve is rejected with `IllegalArgumentException` |

### KDF / MAC (HKDF, PBKDF2, HMAC-SHA-2)
| Vector | Defense |
|---|---|
| Skipping extract (IKM as PRK) | **(a)** + **(d)** extract-then-expand enforced |
| `L > 255·HashLen` / `L = 0` edge | **(a)** + **(b)** length cap |
| Non-constant-time tag compare in glue | **(b)** `constantTimeEquals`; **(e)** native verify |
| Hash-length confusion (256/384/512) | **(d)** typed |
| PBKDF2 `c = 0` / `dkLen = 0` / oversized `dkLen` | **(b)** `IllegalArgumentException` in common code before dispatch + negative test |
| PBKDF2 password re-encoded (lossy `char[]` → UTF-8) | **(d)** password is a byte buffer; the JVM path keys the JCA `Mac` directly rather than `PBEKeySpec` + **(a)** non-UTF-8 vector |
| Low PBKDF2 iteration count | **(e)** caller's choice; the KDoc points at the work factor and the suite pins c = 210 000 |

### EC public key from a private scalar (`ecPublicKeyFromScalar`)
| Vector | Defense |
|---|---|
| Scalar `0` or `≥ n` accepted as a key | **(b)** typed `EcEncodingException(ScalarOutOfRange)` in common code before dispatch; the range compare visits every byte + negative test |
| Secret-dependent timing in `d·G` | **(c)/(e)** no scalar arithmetic in this library: BoringSSL `EC_POINT_mul` (Linux), CryptoKit (Apple), WebCrypto PKCS#8 import (web), and on JVM/Android the provider's ECDH against the generator plus an ECDSA sign/verify to pick Y's sign — only public coordinates touch `BigInteger` |
| Scalar copies left in memory | **(e)** the JVM copy is wiped after `ECPrivateKeySpec`; the provider key object, the CryptoKit `Data`, and the web hex String are not wipeable (same limitation as the KA bridges) |
| Wrong point for the scalar | **(a)** RFC 6979 key-pair KATs on every target, including both Y parities |

### HPKE / DHKEM (RFC 9180): Base/PSK/Auth/AuthPSK over X25519, P-256/384/521; AES-128/256-GCM, ChaCha20-Poly1305
| Vector | Defense |
|---|---|
| AEAD nonce reuse across messages | **(d)** per-context monotonic seq → nonce = base_nonce XOR seq; seq advances only on success; `MessageLimitReached` before wrap + **(a)** |
| Plaintext released before tag verified | **(b)/(d)** `open` returns plaintext only after the native AEAD verify (no unverified-plaintext path) + tamper test |
| Tag / ciphertext / AAD / `enc` tamper | **(a)** RFC 9180 KAT + **(c)** decrypt throws `VerificationFailed`; corrupted `enc` rejected as `InvalidPublicKey` or `VerificationFailed` (every-byte-flip test) |
| `suite_id` / label domain-separation error | **(a)** RFC 9180 Appendix A KAT pins `enc`, ciphertext, and exports byte-exact (any label/suite_id byte change diverges) |
| KEM low-order / off-curve / identity point | **(b)/(c)** delegated to the key-agreement family's validation; X25519 all-zero raw secret rejected (constant-time) → `InvalidPublicKey` |
| Raw DH / KEM shared secret leakage | **(d)** the internal `dhRawSecret` seam is `internal`; shared secrets / `secret` / `exporter_secret` / AEAD keys stay in wiped `SecureBuffer`s, never returned or logged |
| Sender-auth (Auth/AuthPSK) forgery | **(a)** KAT + negative test: a receiver authenticating the wrong sender key fails to verify |
| Suite primitive unavailable (ChaCha on web, X25519 where absent) | **(b)/(d)** gated `false` → `UnsupportedOperationException`, never a silent fallback + capability test |

### Resource exhaustion / denial-of-service (cross-cutting)
| Vector | Defense |
|---|---|
| Attacker-controlled length reaches a secure allocation (unbounded native-memory request) | **(b)/(d)** `BufferFactory.secure(maxAllocationBytes = …)` caps every secure `allocate`/`wrap`; an over-limit request throws `IllegalArgumentException` in common code **before** any platform allocation, so the bound is byte-identical on every target. The cap defaults to a 16 MiB backstop (`DEFAULT_MAX_SECURE_ALLOCATION_BYTES`) — secure-by-default — and callers parsing untrusted, length-prefixed input set a tighter, protocol-specific bound. |
| Message-sequence overflow forcing nonce reuse | **(d)** HPKE per-context monotonic seq throws `MessageLimitReached` before wrap (see §3 HPKE) |
| `L > 255·HashLen` HKDF expansion | **(a)/(b)** length cap (see §3 KDF/MAC) |

### Non-exportable & hardware-backed keys (key custody)
| Vector | Defense |
|---|---|
| Private key material readable in process memory | **(d)** a non-exportable key is **not** a `SyncCapable*` type, so no blocking / material-reading op accepts it (a compile error, not a runtime throw); every use routes through a `suspend` gated closure that drives the backend — WebCrypto `CryptoKey`, Android Keystore, Secure Enclave — and the material seam throws for it as a defensive backstop |
| Non-exportable *software* key mis-reported as a secure element | **(d)** custody is one sealed `KeyCustody` value, not free boolean fields, so impossible states (an exportable secure-element key, a software "dedicated element") do not typecheck. WebCrypto `extractable:false` is `NonExportable.Software`; `CryptoCapabilities.hardware` reports `Available` **only** for a real secure element, so a software-isolated key never masquerades as hardware |
| Silent downgrade when the required custody is absent | **(b)/(d)** `keyProvider()` routes per-algorithm to the strongest eligible tier with the software floor as fallback (never a custody throw at the default); a caller that *requires* a stronger tier asserts it with `requireTier(alg, tier)`, which throws the structured, non-secret `InsufficientKeyCustody` rather than degrading |
| HPKE recipient scalar leaking to the process on the web | **(d)** the DHKEM decap `DH(skR, enc)` runs through the key's gated closure (`subtle.deriveBits` over the non-exportable handle); the recipient scalar never enters process memory, and `hpke().openBase(...)` composes unchanged via `hpkeRecipientPrivateKey` |
| Advisory-gate bypass on a provider key | **(b)** the provider evaluates `ProtectedKeySpec.authorization` before **every** gated op, throwing `AuthorizationFailed` (a structured `CryptoMisuseException`) on deny — never proceeding with the crypto op |

## 4. Platform support & capability gating

These are tested error paths, not assumptions — the capability flag drives a `true`→works /
`false`→throws test on every target.

| Primitive | JVM | Android (minSdk 28) | Apple | JS / WASM |
|---|---|---|---|---|
| SHA-256/384/512, HMAC, HKDF | ✓ | ✓ | ✓ | ✓ (pure-Kotlin core) |
| EC public key from scalar (P-256/384/521) | ✓ (ECDH vs G + ECDSA parity) | ✓ (ECDH vs G + ECDSA parity) | ✓ (CryptoKit) | ✓ (WebCrypto PKCS#8 → JWK, async) |
| PBKDF2-HMAC-SHA256/512 | ✓ (JCA `Mac` loop) | ✓ (JCA `Mac` loop) | ✓ (`CCKeyDerivationPBKDF`) | ✓ (WebCrypto `deriveBits`, async) |
| AES-GCM 128/256 | ✓ | ✓ | ✓ | ✓ (WebCrypto, async) |
| ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ⚠ pending (see §5) | ✗ throws — not in WebCrypto |
| AES-ECB (single block, raw — unauthenticated) | ✓ | ✓ | ✓ | ✗ throws — no ECB in WebCrypto |
| ECDSA P-256/384/521 (verify) | ✓ | ✓ | ✓ | ✓ |
| ECDSA signing | ✓ | ✓ | ⚠ from-scalar gated (see §5) | ✓ |
| Ed25519 | ✓ (JDK 15+) | ✓ **API 34+** (runtime gate, throws 28–33) | ⚠ pending (see §5) | ✓ newer engines (feature-detected) |
| X25519 | ✓ (JDK 11+) | ✓ **API 34+** (runtime gate) | ⚠ pending (see §5) | ✓ newer engines (feature-detected) |
| ECDH P-256/384/521 | ✓ | ✓ | ✓ | ✓ |
| HPKE/DHKEM(P-256/384/521) | ✓ | ✓ | ✓ (ECDH KEMs) | ✓ (WebCrypto async) |
| HPKE/DHKEM(X25519) | ✓ (JDK 11+) | ✓ **API 34+** | ✗ throws (X25519 pending, see §5) | ✓ newer engines (feature-detected) |
| HPKE AEAD = ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ⚠ pending (see §5) | ✗ throws — not in WebCrypto |

Encoding differs by platform and is pinned + tested both ways: JCA uses DER/ASN.1 (ECDSA) and
SPKI/X.509 (ECDH); WebCrypto uses raw P1363 `r‖s` and raw points. HPKE keys use the RFC 9180 raw
KEM encodings (raw X25519 u-coordinate, uncompressed SEC1 EC points); the RFC 9180 Appendix A
known-answer vectors (raw private scalars) run on JVM/Android, and Apple/web get HPKE round-trip
coverage with platform-generated keys.

### Key custody (non-exportable & hardware-backed keys)

Separate from *op* availability above is **key custody** — where a private key's secret lives and
whether this process can read it. `CryptoCapabilities.keyProvider()` is **total**: it always returns
a usable `KeyProvider`, resolving each algorithm to the strongest custody the platform offers with a
commonMain software floor beneath it (so it never returns `null` and never throws for custody reasons
at the default). The tiers, weakest → strongest (`CustodyTier`): `ExportableSoftware` <
`NonExportableSoftware` < `Hardware`.

| Platform | Strongest custody | Backing | Algorithms at that tier |
|---|---|---|---|
| JVM / Linux | `ExportableSoftware` | in-process software keys | (floor) all — no portable non-exportable store exists |
| **JS / WASM** | `NonExportableSoftware` | **WebCrypto `extractable:false`** — software isolation, *not* a secure element | ECDSA P-256/384/521, AES-GCM, ECDH P-256/384/521; X25519 where the engine exposes it (feature-detected). Ed25519 excluded (uneven engine support) → routes to the floor |
| Android (API 28+) | `Hardware` | Android Keystore — StrongBox secure element where it holds the key, else TEE (read back per key) | AES-GCM, ECDSA P-256 |
| Apple | `Hardware` | Secure Enclave | ECDSA P-256 |

Whatever an algorithm's platform tier does not back routes **down** to the next eligible tier
(ultimately the software floor), so `keyProvider().generateSigning(EcdsaP521, …)` on a secure element
that backs only P-256 transparently produces a software key instead of throwing. Keys are
self-describing — each carries its own `KeyCustody` — so a consumer can `when`-branch on, or assert
(`requireTier`) the custody it actually received.

**Non-exportable ≠ hardware.** Exportability (can the process read the secret?) and provenance (where
the secret lives) are orthogonal axes. A WebCrypto key is non-exportable *software*: the process heap
never sees the private bytes, yet it is not a dedicated secure element. Filing it under `Hardware`
would corrupt the signal the `hardware` accessor carries, so it is `NonExportable.Software`, surfaced
through `CryptoCapabilities.protectedKeys` (the non-exportable superset) but **not**
`CryptoCapabilities.hardware` (the secure-element-only refinement). Desktop JVM / Linux have no
portable non-exportable store (DPAPI / Keychain / Secret Service are all platform-native, none
reachable from pure KMP), so they stay at the exportable software floor — a key-on-disk is
exportable and is never surfaced behind `protectedKeys` / `hardware`.

### Key persistence (`KeyStore`)

`CryptoCapabilities.keyStore(config)` adds an alias-addressable *persistent* lifecycle on top of the
ephemeral `KeyProvider`: get-or-generate-by-alias (idempotent), load-by-alias across launches, and
delete, for signing / AES-GCM / key-agreement keys. `KeyStore : KeyProvider`, so the same custody
tiers and `custodyFor` / `requireTier` assertions above apply to a persisted key — the custody a
`KeyStore` reports is the custody the *stored* key actually has.

- **Persistence never fabricates custody.** A persisted key reports its true tier. A device-identity
  consumer that requires hardware asserts `requireTier(alg, CustodyTier.Hardware)` and is refused
  (structured `InsufficientKeyCustody`) on any platform whose store is weaker — it never silently
  persists a downgraded key.
- **Persistence fuses with custody on non-exportable tiers.** On a secure element / non-extractable
  WebCrypto tier the key never becomes an exportable blob, so *where* it persists is inseparable from
  *who holds it* — it is the OS store itself, not a pluggable medium. Only the `ExportableSoftware`
  tier separates the two: there the key is a PKCS#8 DER blob whose medium is pluggable via
  `KeyStoreConfig.storage` (a `KeyStorage` blob SPI). The SPI is **ignored** on OS-backed tiers,
  precisely because there is no exportable blob to relocate.
- **`ExportableSoftware` persistence is exportable at rest — by definition.** The default JVM / Android
  medium writes one PKCS#8 DER file per alias under an owner-only directory. Those bytes are the
  private key; this is the `ExportableSoftware` tier and is labelled as such. Pointing
  `KeyStoreConfig.storage` at an encrypted store hardens it, and is the intended path where at-rest
  protection is required without a hardware tier.
- **No silent replace.** `getOrGenerate*` over an alias already holding a different algorithm throws
  `KeyStoreException.AliasMismatch` (a device-identity key cannot be clobbered by a mistyped call);
  regeneration is an explicit `delete` + `getOrGenerate`. A persisted key's `close()` releases only
  the in-process handle — the stored key survives until `delete`.

Coverage: `KeyStoreConformanceTest` (commonMain, every platform) exercises idempotency, load-after-
reload, delete, alias mismatch, custody equals `custodyFor`, and the persist-survives-`close`
invariant; `FileKeyStoreTest` (JVM) and `PosixFileKeyStoreTest` (Linux) exercise a real on-disk
round-trip across store instances; `KeyStoreInstrumentedTest` (`connectedCheck`) pins the Android
Keystore hardware store on a device — reload across a simulated restart, hardware custody, and that a
persistent key's `close()` does not delete the entry.

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
- **Linux** — the native target wraps BoringSSL (not OpenSSL), consistent with the sibling
  networking module, at the `ExportableSoftware` tier (no portable non-exportable store exists there).
  The BoringSSL binary is **not built in this repository**: it is a checksum-pinned prebuilt bundle
  provisioned from [`DitchOoM/boringssl-kmp`](https://github.com/DitchOoM/boringssl-kmp) (one
  canonical, single-pinned commit shared across `buffer`/`socket`), verified by SHA-256 before use.
  See §8 for how that external artifact's provenance folds into the build-integrity model.
- **`KeyStore` durable backends per platform** — the persistent `KeyStore` (see §4) now ships a
  durable backend on **every** target: a durable on-disk `ExportableSoftware` medium on JVM (one
  PKCS#8 DER file per alias) and Linux (the same, over POSIX stdio); a **`NonExportable.Hardware`**
  store on **Android** (an `AndroidKeyStore` entry keyed `<name>:<alias>` — StrongBox where present,
  else TEE) and **Apple** (a Secure Enclave P-256 key whose restore record is held in a Keychain
  generic-password item keyed by `kSecAttrService`/`kSecAttrAccount`); and a **`NonExportable.Software`**
  store on **web** (a non-extractable WebCrypto `CryptoKey` held in IndexedDB). Each store reports its
  true custody, so a `requireTier(Hardware)` caller is refused rather than silently downgraded on a
  weaker platform. Availability is probed, not assumed: the web store requires both `crypto.subtle`
  and IndexedDB, Android needs an `AndroidKeyStore` provider (absent in a host-JVM unit run), and
  Apple needs CryptoKit to report a Secure Enclave. Only an environment that **has no such backend at
  all** uses a software medium, and it reports the weaker tier honestly: bare Node without IndexedDB, a
  host JVM without `AndroidKeyStore`, and every Apple host without an Enclave (the simulators, an Intel
  Mac without a T2 chip, a virtual machine). The Apple medium is in-process and does **not** survive a
  process restart. Where the backend exists but refuses — a keystore that fails to load, an Enclave probe
  that fails — the store **never** falls back: the call fails with a typed `KeyStoreException`
  (`StorageFailure(retryable = true)`, `AccessDenied` for a binary without the Enclave entitlement, or
  `DeviceLocked` for an Enclave probe the OS refused with `errSecInteractionNotAllowed`) and the backend is
  opened again on the next call. A consumer that needs durability without the platform backend supplies
  its own `KeyStoreConfig.storage`. (JVM and Linux always use their durable on-disk store.)
- **Android custody is read back per key.** After every generation and reattach the store reads the
  key's `KeyInfo` security level. A key the call just generated must be at `TRUSTED_ENVIRONMENT`,
  `STRONGBOX` or `UNKNOWN_SECURE` (secure hardware of an unnamed kind, at the TEE floor); any other level
  deletes it and fails with `InsufficientKeyCustody`, naming what the keystore reported:
  `OfferedCustody.Tier(NonExportableSoftware)` for a software level, `OfferedCustody.Unreported` for a level
  it will not name (`InsufficientKeyCustody.available` is then `ExportableSoftware`). A key persisted
  earlier is never refused or deleted for its level, so a key an earlier release stored outside secure
  hardware stays usable: it is handed out as `NonExportable.Software` (the keystore never exports it, and
  nothing vouches for hardware), and a caller that needs hardware checks the key's own `custody`.
  `KeyInfo` is the keystore's own report: only key attestation, verified off the device, proves where a key
  lives. Before API 31 a secure key counts at the TEE floor, since a StrongBox request can be served in the
  TEE without a word. The provider's `custody` (and so `custodyFor`) is the TEE floor; the deprecated
  provider-wide `HardwareKeyProvider.dedicatedSecureElement` is the one-time StrongBox probe it was in the
  last release (always `true` on Apple); each key's `custody` says whether StrongBox holds it.
- **StrongBox falls back to the TEE, with the reason recorded.** A transient StrongBox refusal gets one more StrongBox
  attempt (two in all), then the key is made in the TEE; every other refusal goes to the TEE at once. A key made that
  way carries `SecureElementFallback.ToWeakerSecureTier(reason, detail)` (the `secureElementFallback` member of
  `SigningKey`, `AesGcmKey` and `KeyAgreementPrivateKey`), so a caller can log why a key is not in StrongBox. The
  `reason` is platform-neutral (`ElementAbsent`, `UnsupportedParameters`, `ElementBusy`, `ElementFailed`,
  `Unclassified`); the `detail` is what the platform reported, on Android an `AndroidKeystoreFallbackDetail` with the
  keystore's `AndroidKeystoreCode`. Before API 33 the keystore publishes no code, and the fallback says `Unclassified`
  / `Unpublished`. A key reattached from the store reports `SecureElementFallback.NotRecorded`: the keystore keeps no
  record of how its generation went. `None` means no fallback happened.
- **Apple keys and records use the after-first-unlock class.** Enclave keys that are not bound to user authentication
  are created `AfterFirstUnlockThisDeviceOnly` with `privateKeyUsage`; Keychain records are added with the same class.
  On iOS, watchOS and tvOS the records live in the data protection keychain, which enforces the class: they are
  readable while the device is locked after its first unlock since boot, and never leave the device. On macOS no
  query sets `kSecUseDataProtectionKeychain`, so the records live in the legacy file keychain (normally the login
  keychain), which does not enforce protection classes: there a record is as readable, and as copyable with the
  keychain file, as any other item of that keychain. An Enclave key's class is fixed when it is made: a key created
  under an older class keeps it. Records are added, never replaced: two writers racing to create one alias end with
  the one record that was added first; an add refused as a duplicate while no read returns the record is tried once
  more and is then `KeyStoreException.AccessDenied` (an item this process cannot read).
- **Which Apple calls are serialized.** Every keychain call the store makes (add, read, contains, delete, list, class
  move) runs on one dispatcher of parallelism one, so a call blocked inside Security.framework (a macOS access prompt)
  holds one thread while every other store caller waits suspended; inside the shim the same calls run on one serial
  queue, which also covers code calling the shim directly. Two `SecItem` calls running at once against the macOS file
  keychain can deadlock inside Security.framework. Keychain calls made elsewhere in the process are outside both. The
  Enclave generate, sign and agree calls are not serialized: they pass no keychain attributes (CryptoKit returns the
  key as an encrypted blob, and nothing is added to a keychain), and the in-memory `SecKeyCreateRandomKey` /
  `SecKeyCreateWithData` keys of the software paths set no `kSecAttrIsPermanent`, so none of them adds or reads a
  keychain item. Whether Security.framework takes keychain-wide locks internally for them is not something this
  library controls or has measured.
- **Existing Keychain records are moved to the new class without an opt-in.** Records written by earlier releases under
  `WhenUnlockedThisDeviceOnly` are moved to `AfterFirstUnlockThisDeviceOnly` on first use and before a listing; the
  move matches only records still under the earlier class, so records already moved are not rewritten. The move is
  one-way; on iOS-family it makes those records readable while the device is locked, after its first unlock since
  boot. A record is opaque restore data, not a private key: the private scalar stays in the Enclave, and an older key
  keeps its own class. A listing that would silently miss a record the OS withholds with
  `errSecInteractionNotAllowed` is `KeyStoreException.DeviceLocked` instead. The move is asked again only while it may
  still pass (a locked device, a busy or not-yet-available keychain); a store whose move is refused for any other
  reason stops asking.
- **Failures are typed by family.** A failure of a key operation is a `HardwareKeyException`: an Enclave generate,
  sign or agreement the OS refuses with `errSecInteractionNotAllowed` is `HardwareKeyException.DeviceLocked` (observed
  for an access-controlled generation on a Mac whose screen was locked; the other calls' statuses on a locked iPhone
  are not observed here), and an Enclave refused for lack of an entitlement is `HardwareKeyException.AccessDenied`.
  A failure of the store is a `KeyStoreException`. On Android
  that covers the store's lookups, loads, `KeyInfo` reads, listings and deletes: a keystore failure in one of them is
  `StorageFailure(retryable = true)`, while a key generation that fails stays a `HardwareKeyException`. On Apple:

  | Keychain status | `KeyStoreException` |
  |---|---|
  | `errSecInteractionNotAllowed` on iOS, watchOS, tvOS | `DeviceLocked` |
  | `errSecInteractionNotAllowed` on macOS (a prompt this process may not show) | `InteractionRequired` |
  | `errSecUserCanceled`, `errSecAuthFailed` on macOS (the file keychain's access prompt) | `AuthenticationDeclined` |
  | `errSecUserCanceled`, `errSecAuthFailed` on iOS, watchOS, tvOS (no prompt: the records carry no access control) | `StorageFailure(retryable = false)` |
  | `errSecMissingEntitlement`, `errSecNoAccessForItem` | `AccessDenied` |
  | `errSecDecode`, an empty or truncated record | `CorruptEntry` |
  | `errSecIO`, `errSecAllocate`, `errSecNotAvailable` (no keychain yet, as early in boot) | `StorageFailure(retryable = true)` |
  | hardware with no Enclave (settled for the process) | `StorageFailure(retryable = false)` |
  | any other status | `StorageFailure(retryable = false)` |

- **A refused backend is probed again, not on every call.** A refused probe stands for a few seconds (measured on
  `TimeSource.Monotonic` in production), so asking `eligible` or `custodyFor` repeatedly does not mint and discard a
  probe key each time. The Apple agreement probe settles on a definite answer (an entitlement refusal, a declined
  agreement) and, after three retry windows that each ended undecided (a refusal with `errSecInteractionNotAllowed` is
  not counted), settles agreement off. Callers that probe at once during one window count as one.
- **`custodyFor`, `eligible` and `requireTier` may throw and may probe on the caller's thread.** On a platform
  `KeyStore` they open the backend first, so a backend that cannot open right now throws its typed
  `KeyStoreException` from them rather than answering for a store that is not there, and `custodyFor` for an
  algorithm the backend cannot hold throws `HardwareKeyException.AlgorithmNotEligible` rather than name a custody no
  key will have. The first call, and the first call after a refused probe's window, can run the platform probe
  synchronously on the calling thread: on Apple a generate-and-discard Enclave key (and, for `EcdhP256`, one
  agreement); on Android the keystore load and, for `EcdhP256`, a throwaway agreement key. Call them off the main
  thread where that matters.
- **Android get-or-generate never writes a key over another, and a stored key is never reported absent.** Every
  lookup goes through `getKey` (which answers `null` only for a missing key), not `containsAlias` or `getEntry`:
  `AndroidKeyStoreSpi` answers both of those from a metadata read that logs any keystore error and returns `null`, so
  they answer `false` / `null` for a key that is there. A get-or-generate looks the alias up and generates in the same
  keystore critical section, so two callers in one process cannot both generate under one alias, and a generation
  that fails deletes only the key it made. A lookup that fails, or finds a key of a type the store never writes (only
  P-256 keys are; an RSA or P-384 key is another type), refuses rather than generating over it. `contains`, `delete`
  and the loads read the alias the same way: a keystore that cannot answer is `StorageFailure(retryable = true)`,
  never a `false` or a `null` while a key is stored, and a load whose entry read then misses the key the lookup found
  is that failure too. `aliases()` is the exception the platform imposes: `AndroidKeyStoreSpi` ends its listing early,
  without an error, when the keystore fails partway, so a listing can miss aliases. Separate processes of one app
  share the keystore without this lock.
- **Desktop JVM / Linux non-exportable custody** — desktop JVM / Linux expose no portable
  non-exportable store (DPAPI / Keychain / Secret Service are platform-native, none reachable from
  pure KMP), so both `keyProvider()` and `keyStore()` resolve to the exportable software tier (see
  §4). This is by design, not a capability that throws; a key-on-disk is exportable and is
  deliberately never surfaced behind `protectedKeys` / `hardware`.

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

## 8. Supply-chain & build integrity

The threat model extends past the source: a consumer must be able to trust that the published
artifact was built from this repository by this CI, untampered. The controls, mapped to STRIDE
**Tampering** and **Denial-of-Service** at the build layer:

- **SLSA build provenance.** Every release attaches a signed provenance attestation
  (`actions/attest-build-provenance`, GitHub OIDC) binding the published jars/klibs/aars to the
  workflow, commit, and runner that produced them. Verify with
  `gh attestation verify <artifact> --repo ditchoom/buffer`.
- **SBOM.** A CycloneDX SBOM is generated from the exact published artifact set, attached to the
  GitHub release, and itself attested.
- **External BoringSSL provenance (Linux).** The Linux crypto backend links a **prebuilt** BoringSSL
  static library obtained from `DitchOoM/boringssl-kmp`, not compiled here — so its integrity is a
  distinct trusted input, not covered by this repo's own build provenance. It is anchored two ways:
  the `com.ditchoom.boringssl.provision` plugin carries the bundle's **SHA-256 baked in** (no
  trust-on-first-use) and verifies the downloaded tarball against it before extraction, and the
  upstream bundle ships its own `.sha256` + `provenance.json` (canonical commit + quiche ABI anchor)
  and is built by that repo's CI from a single pinned commit. A tampered or substituted bundle fails
  the checksum gate at configuration time rather than entering a build.
- **Pinned, immutable actions.** Every third-party GitHub Action is pinned to a full commit SHA
  (with the human-readable tag in a trailing comment); Dependabot updates the pin + comment
  together. A retagged/compromised action cannot silently enter the build.
- **Least-privilege tokens.** Workflows default to a read-only `GITHUB_TOKEN`; write scopes
  (`contents`, `attestations`, `id-token`) are granted per-job only where required. The
  `pull_request_target` release workflow in particular runs read-only except for the single
  tag/release/attestation job.
- **Static analysis & posture scoring.** CodeQL (`security-extended`) runs on every PR and weekly;
  OpenSSF Scorecard runs weekly and on push to `main`, surfacing branch-protection, token, and
  pinning regressions.
- **Signed publication.** Maven Central artifacts are PGP-signed (detached `.asc`) with checksums,
  produced only on `main`.
