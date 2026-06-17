# Crypto threat model & coverage tally

> Living checklist for the `:buffer-crypto` primitive fan-out. Every known attack vector
> against the primitives we wrap, with an explicit **defense classification** so coverage
> is tracked, not assumed. We wrap each platform's **native** stack (JCA/Conscrypt, CryptoKit/
> CommonCrypto, WebCrypto) — **we never hand-roll a primitive.** Naming guardrail still applies:
> never reference MLS / RFC 9420 anywhere user-visible.

## What we guarantee vs. what we delegate

The honest guarantee stack, in order of strength:

1. **Native-or-throw.** No primitive is implemented by us; an unavailable algorithm throws
   `UnsupportedOperationException` and its capability flag is `false`. (Sole exception: the
   pure-Kotlin SHA-256/HMAC fallback on JS/WASM, already landed, because WebCrypto's digest
   is async-only.)
2. **Cross-platform correctness** is proven by **KAT (RFC/NIST) + Wycheproof** vectors run on
   *every* target. Same vectors passing everywhere ⇒ transitive cross-platform consistency.
3. **Negative/tamper** tests prove failures *throw* (never silently pass) — this exercises
   *our* verification branch, which KAT never does.
4. **Misuse resistance** comes from typed keys + CSPRNG-only nonces + runtime checks.

What we explicitly **do not** guarantee (documented trust boundaries, class **(e)** below):
side-channel / timing / fault resistance of the native primitive; constant-time execution of
the platform's own verify. We trust the platform for these and say so.

## Defense classification legend

- **(a)** KAT / Wycheproof vector test
- **(b)** runtime check in *our* wrapper glue
- **(c)** delegated to platform primitive **+ asserted** by a negative test
- **(d)** prevented by API / type design (typed keys, CSPRNG-only nonce, fixed tag length)
- **(e)** out of scope / documented trust assumption (side-channel, fault, native timing)

## Exception contract (cross-platform normalization)

`sealed CryptoException` (see `CryptoException.kt`) normalizes platform-divergent failures:

- `VerificationFailed` — **opaque & uniform**, no reason, no cause. All AEAD-tag / signature /
  authenticated-decrypt failures collapse to this. Granular reasons on a verify path = oracle.
- `CryptoMisuseException` (sealed) — **structured & granular** (public, non-secret detail):
  - `InvalidPublicKey(curve)` — low-order / off-curve / identity point, and the X25519
    all-zero-shared-secret rejection.
- **Unsupported** → `UnsupportedOperationException` (capability contract; outside the sealed
  tree by Kotlin single-supertype rule).
- **Input preconditions** (dest too small, bad nonce/key length) → `IllegalArgumentException`
  via `require` in *common* code — thrown before dispatch, so already byte-identical everywhere.

**Invariant:** no secret material (keys, plaintext, shared secrets, derived material) in any
message, property, or cause.

---

## Vector sources to vendor

Wycheproof moved to **C2SP**: `https://github.com/C2SP/wycheproof`, vectors in
`testvectors_v1/`, schemas in `schemas/`. Raw fetch:
`https://raw.githubusercontent.com/C2SP/wycheproof/main/testvectors_v1/<file>.json`.

Per-test fields: `tcId` (int, use in failure reporting), `comment`, `flags[]`, `result` ∈
{`valid`, `invalid`, `acceptable`}, plus hex material (`key`/`msg`/`sig`/`ct`/`tag`/`iv`/`aad`/
`public`/`private`/`shared`).

**`acceptable` handling in the runner:** `valid` → must succeed; `invalid` → must fail
(`VerificationFailed`/reject); `acceptable` → *neither* assertion, but **record the outcome and
pin a per-platform expected decision** so a behavior change is caught as a regression. Where our
policy is stricter than the spec (enforce low-S, reject non-canonical), promote specific
`acceptable`+flag cases to a hard *reject* assertion.

| Family | Files (`testvectors_v1/`) |
|---|---|
| Hashes/HMAC/HKDF | `hmac_sha256_test.json`, `hmac_sha384_test.json`, `hmac_sha512_test.json`, `hkdf_sha256_test.json`, `hkdf_sha384_test.json`, `hkdf_sha512_test.json` |
| AES-GCM | `aes_gcm_test.json` |
| ChaCha20-Poly1305 | `chacha20_poly1305_test.json` |
| ECDSA | `ecdsa_secp256r1_sha256_test.json`, `…_p1363_test.json`, P-384/P-521 equivalents |
| Ed25519 | `ed25519_test.json` |
| X25519 | `x25519_test.json` |
| ECDH | `ecdh_secp256r1_test.json` (+ `…_webcrypto_test.json` raw-point), P-384/P-521 |
| HPKE | `hpke_*_test.json` (confirm exact names against tree — newest/least stable) |

---

## Platform support & gating matrix (these are tested error paths, not assumptions)

| Primitive | JVM | Android (minSdk 28) | Apple | Web (JS/WASM) |
|---|---|---|---|---|
| SHA-384/512, HMAC, HKDF | ✓ | ✓ | ✓ | ✓ (pure-Kotlin fallback) |
| AES-GCM 128/256 | ✓ | ✓ | ✓ | ✓ (WebCrypto) |
| ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ✓ | ✗ **throws** — not in WebCrypto (w3c/webcrypto #223 open); never polyfill |
| ECDSA P-256/384/521 | ✓ | ✓ | ✓ | ✓ |
| Ed25519 | ✓ **JDK 15+** | ✓ **API 34+** — runtime `SDK_INT` gate, throws 28–33 (Conscrypt added X25519/Ed25519 in Android 14) | ✓ | ✓ newer engines (Chrome 137/Firefox 129/Safari 17; Node stable) — **feature-detect + error path** |
| X25519 | ✓ **JDK 11+** | ✓ **API 34+** — same gate | ✓ | ✓ same as Ed25519 |
| HPKE | composed | composed (gated by its KEM) | composed | composed |

**Action items these create:** ChaCha web = unsupported-and-throws (capability flag `false`);
Android X25519/Ed25519 = runtime `SDK_INT >= 34` gate (flag reflects runtime, not compile);
ECDSA encoding differs by platform (JCA = DER/ASN.1, WebCrypto = raw P1363 r‖s) → pin expected
encoding per platform and test both Wycheproof encodings.

---

## Attack catalogue (defense per vector)

### AEAD — AES-GCM 128/256
| Vector | Impact | Defense |
|---|---|---|
| Nonce reuse (forbidden attack / Joux) | recovers GHASH key `H` ⇒ universal forgery | **(d)** CSPRNG-only IV via typed API + **(b)** counter guard on explicit-nonce overload |
| Nonce length ≠ 96 bits | widens misuse surface | **(d)** enforce 12-byte IV + **(a)** |
| Tag truncation (<128-bit) | weak forgery resistance | **(d)** fixed 16-byte tag + **(a)** |
| Tag/ciphertext bit-flip | must fail | **(a)** + **(c)** assert decrypt throws |
| Plaintext released before tag verified | CCA oracle | **(b)** one-shot, no unverified-plaintext release; **(e)** native streaming semantics |
| 2³² block / ~64 GiB-per-key limit | confidentiality break on counter wrap | **(b)** per-message byte cap + **(e)** documented |
| AAD swap / unauthenticated AAD | context confusion | **(a)** + **(c)** |
| Empty plaintext/AAD edge | length off-by-one | **(a)** |
| Key-size confusion (128 vs 256), all-zero key | silent security downgrade | **(d)** typed key sizes + **(a)** |
| AES/GHASH timing side-channel | key recovery | **(e)** trust AES-NI/CLMUL |

### AEAD — ChaCha20-Poly1305
| Vector | Impact | Defense |
|---|---|---|
| Nonce reuse | keystream + Poly1305 one-time-key reuse ⇒ forgery | **(d)** CSPRNG-only + **(a)** |
| Tag forgery / bit-flip | must fail | **(a)** + **(c)** |
| Counter overflow (>256 GiB) | block-counter wrap | **(b)** + **(e)** |
| IETF (96-bit) vs original (64-bit) nonce confusion | nonce-space reduction | **(d)** pin IETF, reject non-12-byte |
| XChaCha vs ChaCha nonce-size confusion | 24 vs 12 mix | **(d)** typed (only if XChaCha exposed) |
| AAD swap | context confusion | **(a)** + **(c)** |
| WebCrypto absence | silent JS polyfill = forbidden | **(b)/(d)** throw on JS/WASM, never polyfill |

### Signatures — ECDSA P-256/384/521
| Vector | Impact | Defense |
|---|---|---|
| Malleability (s vs n−s) | non-unique sig breaks txid-style IDs | **(a)** + **(b)** enforce low-S if caller needs uniqueness (documented) |
| k reuse / biased nonce | private-key recovery (PS3, Minerva) | **(c)/(e)** trust native RFC-6979/CSPRNG |
| Non-canonical DER (leading zeros, trailing garbage, indefinite len) | malleability / parser confusion | **(a)** — primary Wycheproof value |
| r=0 / s=0 / r,s ≥ n | forgery | **(a)** |
| P1363 (raw r‖s) vs ASN.1 confusion | cross-platform misparse | **(a)** + **(d)** pin per-platform encoding |
| Point not on curve / infinity as pubkey | invalid-curve forgery | **(a)** + **(c)** |
| Curve↔hash mismatch | weakened / interop break | **(d)** bind curve↔hash in typed API |
| Special-value sigs (r=1, edge u/v) | verifier edge bugs | **(a)** |
| Scalar-mult timing | key recovery | **(e)** |

### Signatures — Ed25519
| Vector | Impact | Defense |
|---|---|---|
| Cofactored vs cofactorless divergence | same sig accepted by one verifier, rejected by other ⇒ consensus split | **(c)/(a)** delegate + assert chosen behavior; **document axis per platform** |
| Non-canonical S (missing `S < L`) | malleability (S, S+L both verify) | **(a)** + **(c)** |
| Non-canonical R / A encoding (y ≥ p, sign-bit) | impls diverge | **(a)** |
| Small-order / mixed-order A or R | non-binding: one sig verifies under multiple keys/msgs | **(a)** + **(c)** |
| Batch vs single incompatibility | split-view / DoS | **(e)/(c)** expose single-verify only; document if batch added |
| Key-substitution / non-binding | repudiation | **(a)** |
| Ed25519 vs Ed25519ph/ctx confusion | cross-protocol forgery | **(d)** pin pure Ed25519 |
| Fault during deterministic signing | key leak | **(e)** |

### Key agreement — X25519
| Vector | Impact | Defense |
|---|---|---|
| Low-order / small-subgroup public point | forces low-order shared secret / key-control | **(a)** + **(b)** all-zero-output rejection |
| All-zero shared secret (RFC 7748 §6.1) | attacker forces known key | **(b)** explicit check in glue → `InvalidPublicKey` + **(a)** |
| Non-canonical u-coord / unmasked MSB | impl divergence | **(a)** + **(c)** |
| Scalar not clamped | small-subgroup leakage | **(c)** (clamp is native) + assert via vectors |
| Twist attack | partial key info | **(a)** + **(c)** (ladder+cofactor make safe) |

### Key agreement — ECDH P-256/384/521
| Vector | Impact | Defense |
|---|---|---|
| Invalid-curve attack (off-curve point) | small-subgroup ⇒ private-key recovery | **(a)** + **(c)** assert platform rejects |
| Point at infinity / identity pubkey | zero/known shared secret | **(a)** + **(c)** |
| Small-subgroup confinement | partial key leak | **(a)** + **(c)** |
| Compressed vs uncompressed / SPKI vs raw confusion | parser mismatch (WebCrypto raw vs JCA SPKI) | **(a)** + **(d)** pin per-platform encoding |
| Raw shared secret used without KDF | non-uniform key | **(b)/(d)** API forces HKDF on shared secret |
| Cross-curve param mismatch | confusion | **(d)** typed curves |
| Scalar-mult timing | key recovery | **(e)** |

### KDF / MAC — HKDF, HMAC-SHA-2
| Vector | Impact | Defense |
|---|---|---|
| Skipping extract (IKM as PRK) | non-uniform key | **(a)** + **(d)** force extract-then-expand |
| Salt/info omission, no domain separation | cross-protocol key reuse | **(a)** + **(d)** require explicit `info` |
| L > 255·HashLen | spec violation | **(a)** + **(b)** length cap |
| L = 0 edge | off-by-one | **(a)** |
| Truncated MAC accepted | reduced forgery resistance | **(a)** + **(d)** |
| Non-constant-time tag compare in glue | timing oracle ⇒ forgery | **(b)** `constantTimeEquals`; **(e)** native verify |
| Key > blocksize → hashed; short key | interop / weak key | **(a)** + **(c)** |
| Hash-length confusion (256/384/512) | mismatched verify | **(d)** typed |

### Composition — HPKE / DHKEM (RFC 9180)
| Vector | Impact | Defense |
|---|---|---|
| Sequence-number overflow in seal/open | nonce reuse on wrap | **(b)** error before wrap + **(a)** |
| KEM shared secret = identity/low-order | forced known KEM secret | **(a)** + **(b)** inherits X25519/ECDH defenses |
| Mode confusion (Base/PSK/Auth/AuthPSK) | auth bypass / key confusion | **(d)** typed modes + **(a)** |
| KDF label / suite_id domain-separation error | cross-suite key reuse | **(a)** + **(c)** |
| Ciphersuite downgrade (KEM/KDF/AEAD ids) | weaker negotiated suite | **(d)** pin suite + **(a)** |
| AAD swap in seal/open | inherits AEAD | **(a)** + **(c)** |
| Exporter-secret / context reuse | key reuse | **(b)/(d)** |
| `enc` malleability / invalid encapsulation | KEM break | **(a)** + **(c)** |

---

## Per-PR security checklist (gate before merge)

- [ ] KAT (RFC/NIST) green on JVM + JS + WASM
- [ ] Wycheproof vector set vendored & green (`valid`→pass, `invalid`→reject, `acceptable`→pinned)
- [ ] Negative/tamper: every single-byte flip of tag/ct/AAD/sig throws `VerificationFailed`
- [ ] Capability contract: flag `true`→works, flag `false`→`assertFailsWith<UnsupportedOperationException>`
- [ ] Backing matrix (heap × direct × pooled × slice) for every op
- [ ] Secret compares use `constantTimeEquals` — never `contentEquals`/`mismatch`
- [ ] Typed keys; CSPRNG nonces by default; explicit-nonce only as loud advanced overload
- [ ] Secrets in `SecureBuffer`, wiped on close; no secrets in messages/logs/exceptions
- [ ] `/security-review` clean; ktlint clean; no MLS/RFC-9420 naming

## Coverage status

| Family | Status |
|---|---|
| SHA-256 / HMAC / HKDF / CSPRNG / SecureBuffer | ✅ landed (foundation) |
| `constantTimeEquals` | ✅ landed |
| `CryptoException` hierarchy | ✅ landed (this branch) |
| Shared test base (backings / capability / tamper / Wycheproof runner) | 🔧 in progress |
| SHA-384/512 (+ HMAC/HKDF over them) | ⏳ next (reference PR) |
| AES-GCM, ChaCha20-Poly1305 | ⏳ fan-out |
| ECDSA, Ed25519 | ⏳ fan-out |
| X25519, ECDH | ⏳ fan-out |
| HPKE / DHKEM | ⏳ compose last |
