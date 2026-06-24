---
sidebar_position: 8
title: Cryptography
---

# Cryptography

The `buffer-crypto` module provides cross-platform cryptographic primitives that operate directly on `ReadBuffer`/`WriteBuffer`, so key material and plaintext never have to be copied out into a `ByteArray` first.

Every primitive wraps the platform's **vetted native stack** — JCA/Conscrypt (JVM/Android), CryptoKit + CommonCrypto + Security framework (Apple), BoringSSL (Linux), and WebCrypto (JS/WASM). Nothing cryptographic is hand-rolled. If a platform cannot provide an algorithm, the call throws `UnsupportedOperationException` and the matching `supports…` capability flag is `false` — never a silent fallback.

:::tip Security model
This page is a cookbook. For the full threat model, attack-vector coverage, exception contract, and supply-chain integrity controls, read the standing [`buffer-crypto/SECURITY.md`](https://github.com/DitchOoM/buffer/blob/main/buffer-crypto/SECURITY.md). A condensed [Security model](#security-model) section is at the bottom of this page.
:::

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-crypto:<latest-version>")
}
```

## Seal / Open (AEAD)

Authenticated Encryption with Associated Data covers AES-GCM (128/256-bit keys) and ChaCha20-Poly1305 (IETF).

The self-framing `seal` helpers draw a fresh 12-byte CSPRNG nonce, prepend it, and return a self-describing `nonce ‖ ciphertext ‖ tag` buffer — so a caller can never accidentally reuse a `(key, nonce)` pair, and `open` needs no separate nonce argument. Plaintext is released **only after the tag verifies**; a bad tag, tampered ciphertext, swapped AAD, or wrong key all surface as the same opaque `VerificationFailed`.

### AES-GCM

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.toReadBuffer

// A 256-bit key (32 bytes). In practice this comes from a KDF or key agreement.
val key = AesGcmKey.of(cryptoRandom(AES_256_KEY_BYTES))

val plaintext = "attack at dawn".toReadBuffer()
val aad = "header-v1".toReadBuffer() // authenticated, not encrypted (optional)

// seal -> nonce ‖ ciphertext ‖ tag
val sealed = aesGcmSeal(key, plaintext, aad)

// open -> recovered plaintext (only if the tag verifies)
val recovered = aesGcmOpen(sealed, key, aad)
val message = recovered.readString(recovered.remaining())
```

`aesGcmSeal`/`aesGcmOpen` are **synchronous and require `supportsSyncAesGcm`** — `true` on JVM/Android/Apple, `false` on JS/WASM (WebCrypto's `SubtleCrypto` is async-only). On the web, use the async wrappers, which work on **every** platform:

```kotlin
// Works on every platform, including the browser.
val sealed = aesGcmSealAsync(key, plaintext, aad)
val recovered = aesGcmOpenAsync(sealed, key, aad)
```

### ChaCha20-Poly1305

The API mirrors AES-GCM. The key is always 256-bit and is a distinct type (`ChaChaPolyKey`) so the two AEAD families cannot be cross-used.

```kotlin
val key = ChaChaPolyKey.of(cryptoRandom(CHACHA_KEY_BYTES))

val sealed = chaChaPolySeal(key, "secret".toReadBuffer())
val recovered = chaChaPolyOpen(sealed, key)
```

:::warning ChaCha20-Poly1305 is not in WebCrypto
ChaCha20-Poly1305 is **not** part of WebCrypto and is never polyfilled. On JS/WASM both `chaChaPolySeal`/`chaChaPolyOpen` and their async forms throw `UnsupportedOperationException`. Gate on `supportsChaChaPoly` if you target the web. AES-GCM is the portable AEAD choice.
:::

## Sign / Verify

Signature schemes **bind their curve to a single hash**, so a caller can never pair, say, P-256 with SHA-512 — the binding is part of the type. The supported schemes are Ed25519 (pure RFC 8032) and ECDSA over NIST P-256/P-384/P-521.

`SigningKey` holds its material in a wiped `SecureBuffer` and is `AutoCloseable` — treat it as a one-shot resource and close it with `use {}`.

### Ed25519

```kotlin
import com.ditchoom.buffer.crypto.*

// 32-byte raw private seed and the matching 32-byte raw public key.
val message = "transfer 100".toReadBuffer()

SigningKey.ed25519(seedBuffer).use { signingKey ->
    val signature = sign(signingKey, message) // read-ready 64-byte signature

    val verifyKey = VerifyKey.ed25519(publicKeyBuffer)
    val ok: Boolean = verify(verifyKey, message, signature)
}
```

`verify` returns `true` only if the signature is valid; a tampered signature/message/public key returns `false` — it is never accepted, and malformed inputs (non-canonical DER, `r=0`, off-curve points) are rejected by the platform and also surface as `false`.

### ECDSA

```kotlin
// P-256 takes a 32-byte raw private scalar; the verify key is an uncompressed
// SEC1 point (04 ‖ X ‖ Y, 65 bytes for P-256).
SigningKey.ecdsaP256(privateScalarBuffer).use { signingKey ->
    val signature = sign(signingKey, message)

    val verifyKey = VerifyKey.ecdsaP256(uncompressedPointBuffer)
    val ok = verify(verifyKey, message, signature)
}
```

The synchronous `sign`/`signInto`/`verify` require the scheme's `supportsSync*` flag. On JS/WASM (and Ed25519 on Android below API 34), use the async API, which is available everywhere:

```kotlin
val signature = signAsync(signingKey, message)
val ok = verifyAsync(verifyKey, message, signature)
```

:::note ECDSA signing from a bare scalar
`SigningKey.ecdsaP256/384/521` derive the public point from the private scalar alone. This is supported on every current target — on Apple it is provided by CryptoKit — so `supportsEcdsaSigningFromScalar` is `true` everywhere. The flag is retained for forward-looking feature detection; gate on it if you target a future backend that cannot sign from a scalar. See the capability matrix below.
:::

## Key Agreement (ECDH / X25519)

Elliptic-curve key agreement covers X25519 (RFC 7748) and ECDH over P-256/P-384/P-521.

The **raw Diffie–Hellman output is never returned**. A raw shared secret is not a uniformly random key, so every entry point runs it through HKDF (extract-then-expand) keyed by the shared secret and domain-separated by a required `info` (and optional `salt`), returning the *derived* key material. The raw secret lives in a wiped `SecureBuffer` and is zeroed before return — even on the failure path.

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.toReadBuffer

// Each party generates a key pair from the platform CSPRNG.
val alice = generateKeyPair(KeyAgreementCurve.X25519)
val bob = generateKeyPair(KeyAgreementCurve.X25519)

val info = "my-app session key v1".toReadBuffer() // domain separation (required)

// Alice derives 32 bytes of key material against Bob's public key.
val aliceKey = deriveSharedSecret(
    privateKey = alice.privateKey,
    peerPublicKey = bob.publicKey,
    info = info,
    length = 32,
)

// Bob derives the same 32 bytes against Alice's public key.
val bobKey = deriveSharedSecret(bob.privateKey, alice.publicKey, info, 32)

// aliceKey and bobKey are byte-identical. Close the pairs to wipe the private keys.
alice.close()
bob.close()
```

Peer public keys are validated before use: X25519 all-zero shared secrets (low-order points, RFC 7748 §6.1) and ECDH off-curve / infinity / small-subgroup points are rejected with `InvalidPublicKey`. To exchange or persist a public key, use its encoding (`KeyAgreementCurve.publicKeyBytes` long) and rebuild it with `KeyAgreementPublicKey(curve, encoded)`; import a private key with `importPrivateKey(curve, encoded)`.

On the web (no synchronous KA), use `generateKeyPairAsync` / `deriveSharedSecretAsync`, which carry the identical KDF-on-shared-secret and validation contract.

## HPKE (RFC 9180)

Hybrid Public Key Encryption is composed entirely over the primitives above (DHKEM over the key-agreement family, HKDF for the key schedule, and the AEAD layer). A suite is a `kem` + `kdf` + `aead` triple, and the whole API is `suspend` so it covers the web.

The single-shot Base-mode helpers are the high-level entry points: encapsulate to the recipient's public key, encrypt, and frame `enc ‖ ciphertext`.

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.toReadBuffer

val suite = HpkeSuite(
    kem = HpkeKem.DhkemX25519HkdfSha256,
    kdf = HpkeKdf.HkdfSha256,
    aead = HpkeAead.Aes128Gcm,
)

// Recipient generates a long-term key pair; the public key is shared with senders.
val recipient = hpkeGenerateKeyPair(suite.kem)

val info = "app context".toReadBuffer()
val plaintext = "hello recipient".toReadBuffer()
val aad = "v1".toReadBuffer()

// Sender: seal to the recipient's public key.
val sealed: HpkeSealed = hpkeSealBase(
    suite = suite,
    recipientPublicKey = recipient.publicKey,
    info = info,
    plaintext = plaintext,
    aad = aad,
)
// Transmit sealed.enc (the encapsulated key) and sealed.ciphertext.

// Recipient: open with the private key.
val recovered = hpkeOpenBase(
    suite = suite,
    recipientPrivateKey = recipient.privateKey,
    enc = sealed.enc,
    info = info,
    ciphertext = sealed.ciphertext,
    aad = aad,
)
val message = recovered.readString(recovered.remaining())

recipient.close() // wipes the private key
```

For a multi-message session, set up a context once and reuse it. The context owns a monotonic per-message sequence number — the nonce is `base_nonce XOR seq`, advanced only on success — so nonce reuse is impossible by construction, and `MessageLimitReached` is thrown before the counter could wrap:

```kotlin
val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
val ct1 = sender.context.seal("message one".toReadBuffer())
val ct2 = sender.context.seal("message two".toReadBuffer())

val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)
val pt1 = receiver.open(ct1)
val pt2 = receiver.open(ct2)
```

All four RFC 9180 modes are exposed via the matching setup functions: `hpkeSetupBaseSender`/`Receiver` (Base), `hpkeSetupPskSender`/`Receiver` (PSK, via `HpkePsk.of`), `hpkeSetupAuthSender`/`Receiver` (sender authentication), and `hpkeSetupAuthPskSender`/`Receiver` (both). A context can also derive independent secrets with `context.export(exporterContext, length)` (RFC 9180 §5.3).

Suite availability differs by platform — gate with `hpkeSupported(suite)` (or `suite.isSupported`). A suite is usable only if its KEM curve and AEAD are both available: a ChaCha20-Poly1305 suite is unsupported on the web, and an X25519 KEM requires Android API 34+ (see the matrix below).

## SecureBuffer — wipe key material on free

`SecureBuffer` is a `PlatformBuffer` decorator that:

- **zero-initializes** its full backing on allocation (so no slack bytes carry prior contents — crypto code relies on a fresh secure buffer reading as zero), and
- **wipes the full capacity** (not just `remaining()`) when freed.

You never construct one directly; you get one from a secure factory via `BufferFactory.secure()`, which layers over any allocation strategy. For key material, layer it over a **deterministic** factory so the wipe is guaranteed to run, and release it with `use {}`:

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.crypto.secure

val secure = BufferFactory.deterministic().secure()

secure.allocate(32).use { keyBuffer ->
    cryptoRandomInto(keyBuffer)
    keyBuffer.resetForRead()
    val key = AesGcmKey.of(keyBuffer, factory = secure)
    // ... use key ...
} // keyBuffer's 32 bytes are zeroed here, at end of block
```

:::caution Wipe is deterministic, not automatic
There is **no GC finalizer hook** — a `SecureBuffer` that is never explicitly freed is never wiped. Allocate secret-bearing buffers from a deterministic factory and release them with `use {}`. The wipe is also best-effort on GC-managed backings (heap `ByteArray`, JS `Int8Array`), where the runtime may already have copied the bytes; the guarantee is strongest on native/direct backings.
:::

### The 16 MiB DoS cap

`secure()` takes a `maxAllocationBytes` upper bound on any single secure allocation or wrap. Secure buffers hold key material and scratch, which is small; a request larger than the bound is almost always a bug or an attacker-supplied length reaching `allocate`. Enforcing the cap turns an unbounded native-memory request (a denial-of-service vector) into a deterministic `IllegalArgumentException`, thrown in common code **before** any platform allocation — so the behavior is byte-identical on every target.

The cap defaults to a **16 MiB backstop** (`DEFAULT_MAX_SECURE_ALLOCATION_BYTES`) — secure by default, generous enough that no realistic key material approaches it. When an attacker controls the length (e.g. parsing untrusted, length-prefixed input), set a **tighter, protocol-specific** bound:

```kotlin
// Untrusted length-prefixed input: cap secure allocations at 4 KiB.
val secure = BufferFactory.deterministic().secure(maxAllocationBytes = 4 * 1024)

secure.allocate(attackerControlledLength) // throws IllegalArgumentException if > 4096
```

## Don't pool key material

A `BufferPool` reuses buffers across calls — that is exactly what makes pools fast, and exactly why they are the wrong place for secrets. A buffer returned to a pool keeps its bytes; the next borrower can read whatever lingered there, and a pooled buffer is never wiped on `release()`.

**Guidance:**

- **Do not borrow ephemeral key material, plaintext, or derived secrets from a general-purpose pool.** Use the secure path instead — `BufferFactory.deterministic().secure()` zero-inits on allocation and wipes on free.
- Scope the lifetime of secret-bearing buffers tightly with `use {}` so they are wiped at the end of the block.
- The library's own key types (`SigningKey`, `KeyAgreementPrivateKey`, `HpkePrivateKey`, `HpkePsk`) already hold their material in wiped `SecureBuffer`s and are `AutoCloseable` — close them.

```kotlin
// WRONG — secret lingers in a reused buffer after release()
pool.withBuffer(32) { buffer ->
    cryptoRandomInto(buffer)
    // ... buffer goes back to the pool still holding the key bytes ...
}

// CORRECT — secure factory zero-inits and wipes on free
BufferFactory.deterministic().secure().allocate(32).use { buffer ->
    cryptoRandomInto(buffer)
    // ... buffer is zeroed at end of block ...
}
```

## Feature detection — `CryptoCapabilities`

The individual `supports…` flags used throughout this guide (`supportsSyncAesGcm`,
`supportsChaChaPoly`, `supportsSyncEd25519`, …) are gathered behind one discoverable surface,
`CryptoCapabilities`, so you can branch on availability — and pick the synchronous vs `suspend`
path — from a single place. Every member is a thin alias over the same `supports…` source of
truth; the facade just makes them easy to find.

Availability has **two axes**: `anyPath` ("works via *some* path — sync or `suspend` async") and
`sync` ("a non-`suspend` entry point exists"). WebCrypto primitives on JS/WASM are async-only, so
they report `anyPath = true` but `sync = false`.

```kotlin
import com.ditchoom.buffer.crypto.CryptoCapabilities

// AEAD: prefer the sync one-shot, fall back to the async path where only that exists (web).
val sealed =
    if (CryptoCapabilities.aesGcm.sync) aesGcmSeal(key, nonce, plaintext, dest)
    else aesGcmSealAsync(key, nonce, plaintext, dest)

// ChaCha20-Poly1305 is unavailable by *either* path on JS/WASM.
if (CryptoCapabilities.chaChaPoly.anyPath) { /* … */ }

// Signatures and key agreement expose their synchronous availability directly.
if (CryptoCapabilities.ed25519Sync) sign(privateScalar, message, dest)
if (CryptoCapabilities.keyAgreementSync(KeyAgreementCurve.X25519)) { /* … */ }

// ECDSA verify is universal; signing from a bare scalar is feature-detected (true on all current targets).
if (CryptoCapabilities.ecdsaSigningFromScalar) { /* sign from a raw private scalar */ }

// HPKE: check the whole suite (its KEM curve *and* AEAD must both be available).
if (CryptoCapabilities.hpke(suite)) { /* set up sender/receiver */ }
```

`CryptoCapabilities.aead(HpkeAead.Aes256Gcm)` maps an HPKE AEAD id to its `AeadAvailability`. The
per-platform results of these checks are the [capability matrix](#per-platform-capability-matrix)
below.

## Security model

The full threat model lives in [`buffer-crypto/SECURITY.md`](https://github.com/DitchOoM/buffer/blob/main/buffer-crypto/SECURITY.md). In brief:

- **Native-or-throw.** No primitive is hand-rolled. An unavailable algorithm throws `UnsupportedOperationException` and its `supports…` flag is `false`. (One documented exception: a pure-Kotlin SHA-2 / HMAC core on JS/WASM, because WebCrypto's `digest` is async-only — it is pinned by NIST/RFC known-answer vectors.)
- **Correctness** is proven by RFC/NIST known-answer vectors + Wycheproof, run on every target.
- **Opaque verify failures.** Every AEAD-tag / signature / authenticated-decrypt failure collapses to a single `VerificationFailed` with no reason or cause — a granular reason on a verify path is a decryption oracle. Misuse with non-secret detail surfaces as a structured `CryptoMisuseException` (e.g. `InvalidPublicKey`). No secret material ever appears in an exception, property, cause, or log.
- **Resource-exhaustion / DoS.** `BufferFactory.secure(maxAllocationBytes = …)` caps every secure allocation (16 MiB default backstop); HPKE's monotonic sequence prevents nonce reuse and throws `MessageLimitReached` before wrap; HKDF enforces the `L ≤ 255·HashLen` cap.
- **Out of scope (trust boundary):** side-channel / timing / fault resistance of the underlying native primitive, and the constant-time execution of the platform's own verify. The library's *own* secret-dependent comparisons are constant-time.
- **Supply-chain integrity.** Releases attach SLSA build provenance (`gh attestation verify <artifact> --repo DitchOoM/buffer`) and an attested CycloneDX SBOM. Third-party GitHub Actions are pinned to commit SHAs, tokens are least-privilege, CodeQL (`security-extended`) runs on every PR, and OpenSSF Scorecard runs weekly.

### Per-platform capability matrix

Capability flags are tested error paths, not assumptions — the flag drives a `true`→works / `false`→throws test on every target.

| Primitive | JVM | Android (minSdk 28) | Apple | Linux | JS / WASM |
|---|---|---|---|---|---|
| SHA-256/384/512, HMAC, HKDF | ✅ | ✅ | ✅ | ✅ | ✅ (pure-Kotlin core) |
| AES-GCM 128/256 | ✅ | ✅ | ✅ | ✅ | ✅ (WebCrypto, async only) |
| ChaCha20-Poly1305 | ✅ (JDK 11+) | ✅ | ✅ | ✅ | ❌ throws — not in WebCrypto |
| ECDSA P-256/384/521 (verify) | ✅ | ✅ | ✅ | ✅ | ✅ |
| ECDSA signing | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ed25519 | ✅ (JDK 15+) | ✅ **API 34+** (throws 28–33) | ✅ | ✅ | ✅ newer engines (feature-detected) |
| X25519 | ✅ (JDK 11+) | ✅ **API 34+** | ✅ | ✅ | ✅ newer engines (feature-detected) |
| ECDH P-256/384/521 | ✅ | ✅ | ✅ | ✅ | ✅ |
| HPKE/DHKEM (P-256/384/521) | ✅ | ✅ | ✅ | ✅ | ✅ (WebCrypto async) |
| HPKE/DHKEM (X25519) | ✅ (JDK 11+) | ✅ **API 34+** | ✅ | ✅ | ✅ newer engines (feature-detected) |
| HPKE AEAD = ChaCha20-Poly1305 | ✅ (JDK 11+) | ✅ | ✅ | ✅ | ❌ throws — not in WebCrypto |

Legend: ✅ available · ❌ unavailable (capability flag `false`, throws `UnsupportedOperationException`).

Notes:

- **Apple** primitives are provided by CryptoKit (via a Swift `@_cdecl` shim) alongside CommonCrypto and the Security framework. ChaCha20-Poly1305, Ed25519, X25519, and ECDSA signing from a raw scalar are all available — CryptoKit is above the module's deployment floors, so the flags are `true` unconditionally. AES-GCM, ECDSA verify, and ECDH use CommonCrypto/Security.
- **Linux** uses a native BoringSSL backend (statically linked) and has full parity with JVM across every primitive above.
- **Android X25519/Ed25519** were added by Conscrypt in Android 14, so the capability flag is `false` and the call throws on API 28–33. This is a runtime (`SDK_INT`) gate, not compile-time.
- **JS/WASM** gates ChaCha20-Poly1305 off entirely (not in WebCrypto, never polyfilled), and feature-detects Ed25519/X25519 against the engine's WebCrypto.

Encoding differs by platform and is pinned + tested both ways: JCA uses DER/ASN.1 (ECDSA) and SPKI/X.509 (ECDH); WebCrypto uses raw P1363 `r‖s` and raw points. HPKE keys use the RFC 9180 raw KEM encodings.
