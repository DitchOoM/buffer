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
- HPKE / DHKEM (RFC 9180): `HpkeKatTest` (RFC 9180 Appendix A vectors, all four modes),
  `HpkeRoundTripTest`, `HpkeTamperTest`, `HpkeBackingTests`, `HpkeCapabilityTest`
- Non-exportable / hardware-backed keys & custody: `HardwareKeyConformanceTest` (SPI +
  gated-closure machinery, via a fake secure element), `KeyProviderResolverTest` (total
  per-algorithm routing + `requireTier`), `WebCryptoProtectedKeyProviderTest` (the js/wasmJs
  WebCrypto non-exportable provider end-to-end: ECDSA, AES-GCM, ECDH, HPKE `openBase`)
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
| AES-GCM 128/256 | ✓ | ✓ | ✓ | ✓ (WebCrypto, async) |
| ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ⚠ pending (see §5) | ✗ throws — not in WebCrypto |
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
| Android (API 28+) | `Hardware` | Android Keystore — StrongBox secure element where present, else TEE | AES-GCM, ECDSA P-256 |
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
  Apple needs a usable Enclave (absent on the simulator / an unentitled CLI runner) — where the probe
  fails, that platform falls back to its software store and reports the weaker tier honestly.
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
