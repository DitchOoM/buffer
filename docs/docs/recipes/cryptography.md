---
sidebar_position: 8
title: Cryptography
---

# Cryptography

The `buffer-crypto` module provides cross-platform cryptographic primitives that operate directly on `ReadBuffer`/`WriteBuffer`, so key material and plaintext never have to be copied out into a `ByteArray` first.

Every primitive wraps the platform's **vetted native stack** — JCA/Conscrypt (JVM/Android), CryptoKit + CommonCrypto + Security framework (Apple), BoringSSL (Linux), and WebCrypto (JS/WASM). Nothing cryptographic is hand-rolled. If a platform cannot provide an algorithm it is **unrepresentable**, not a runtime guess: every operation is reached through a **capability witness** (a `sealed` value you `when` over), so an absent operation simply has no reachable path on that platform — the variance lives in the type, not in a thrown `UnsupportedOperationException`.

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

## The capability-witness model

Cryptographic **operations** do not live on free functions. Each family exposes a witness off `CryptoCapabilities` that reifies what the current platform supports:

| Family | Witness | States |
|---|---|---|
| AES-GCM | `CryptoCapabilities.aesGcm` → `Aead` | `Blocking` (native) · `AsyncOnly` (web) |
| ChaCha20-Poly1305 | `CryptoCapabilities.chaChaPoly` → `OptionalAead` | `Blocking` · `AsyncOnly` · `Unavailable` |
| Signatures | `CryptoCapabilities.signatures(scheme)` → `SignatureSupport` | `Blocking` · `AsyncOnly` · `Unavailable` |
| Key agreement | `CryptoCapabilities.keyAgreement(curve)` → `KeyAgreementSupport` | `Blocking` · `AsyncOnly` · `Unavailable` |
| HPKE | `CryptoCapabilities.hpke(suite)` → `HpkeSupport` | `Supported` · `Unsupported` |

A `Blocking` witness carries `ops` with **both** the synchronous (`*Blocking` / `signInto` / `sealBlocking`) and the inherited `suspend` entry points. An `AsyncOnly` witness (the web — WebCrypto is `suspend`-only) carries `ops` with the `suspend` entry points alone. You `when` over the witness once and the compiler proves every reachable path is satisfiable.

Only **key construction** stays on plain factories (`AesGcmKey.of`, `SigningKey.ed25519`, `KeyAgreementPublicKey.of`, `Hpke.generateKeyPair`, …) — it never "lacks" on a platform, so it does not belong on a witness.

## Seal / Open (AEAD)

Authenticated Encryption with Associated Data covers AES-GCM (128/256-bit keys) and ChaCha20-Poly1305 (IETF).

The self-framing seal ops draw a fresh 12-byte CSPRNG nonce, prepend it, and return a self-describing `nonce ‖ ciphertext ‖ tag` buffer — so a caller can never accidentally reuse a `(key, nonce)` pair, and `open` needs no separate nonce argument. Plaintext is released **only after the tag verifies**; a bad tag, tampered ciphertext, swapped AAD, or wrong key all surface as the same opaque `VerificationFailed`. The nonce is a fixed 96 bits and the tag a fixed 128 bits on every platform; any other nonce length is rejected and truncated tags are never produced or accepted.

### AES-GCM

`CryptoCapabilities.aesGcm` is always present — `Aead.Blocking` on JVM/Android/Apple/Linux, `Aead.AsyncOnly` on JS/WASM. `when`ing over it gives one body that compiles everywhere (both branches are reachable from a `suspend` function):

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.toReadBuffer

// A 256-bit key (32 bytes). In practice this comes from a KDF or key agreement.
val key = AesGcmKey.of(cryptoRandom(AES_256_KEY_BYTES))

val plaintext = "attack at dawn".toReadBuffer()
val aad = Aad.Of("header-v1".toReadBuffer()) // authenticated, not encrypted (Aad.None to omit)

suspend fun roundTrip(): String {
    val sealed = when (val gcm = CryptoCapabilities.aesGcm) {
        is Aead.Blocking  -> gcm.ops.sealBlocking(key, plaintext, aad) // nonce ‖ ciphertext ‖ tag
        is Aead.AsyncOnly -> gcm.ops.seal(key, plaintext, aad)         // suspend (web)
    }
    val recovered = when (val gcm = CryptoCapabilities.aesGcm) {
        is Aead.Blocking  -> gcm.ops.openBlocking(sealed, key, aad)
        is Aead.AsyncOnly -> gcm.ops.open(sealed, key, aad)
    }
    return recovered.readString(recovered.remaining())
}
```

On native you may skip the `when` and call the blocking ops directly:

```kotlin
val gcm = CryptoCapabilities.aesGcm as Aead.Blocking   // native-only assumption
val sealed = gcm.ops.sealBlocking(key, plaintext)       // Aad defaults to Aad.None
```

The synchronous `sealBlocking`/`openBlocking` accept a `SyncCapableAesGcmKey` (the in-memory key `AesGcmKey.of` returns). A hardware-backed key is an `AesGcmKey` but **not** sync-capable, so it cannot reach the blocking ops — that misuse is a compile error, not a runtime throw. See [Sync-capable keys](#sync-capable-keys-the-hardware-seam).

### ChaCha20-Poly1305

The API mirrors AES-GCM. The key is always 256-bit and is a distinct type (`ChaChaPolyKey`) so the two AEAD families cannot be cross-used. Because ChaCha20-Poly1305 is **absent on the web**, its witness is `OptionalAead` — with an extra `Unavailable` state:

```kotlin
val key = ChaChaPolyKey.of(cryptoRandom(CHACHA_KEY_BYTES))

when (val cc = CryptoCapabilities.chaChaPoly) {
    is OptionalAead.Blocking  -> {
        val sealed = cc.ops.sealBlocking(key, "secret".toReadBuffer())
        val recovered = cc.ops.openBlocking(sealed, key)
    }
    is OptionalAead.AsyncOnly -> { /* (no current platform) */ }
    OptionalAead.Unavailable  -> { /* JS/WASM — not in WebCrypto */ }
}
```

:::warning ChaCha20-Poly1305 is not in WebCrypto
ChaCha20-Poly1305 is **not** part of WebCrypto and is never polyfilled. On JS/WASM `CryptoCapabilities.chaChaPoly` is `OptionalAead.Unavailable`, so no seal/open path is reachable. AES-GCM is the portable AEAD choice.
:::

## Sign / Verify

Signature schemes **bind their curve to a single hash**, so a caller can never pair, say, P-256 with SHA-512 — the binding is part of the type. The supported schemes are Ed25519 (pure RFC 8032) and ECDSA over NIST P-256/P-384/P-521.

A `SigningKey` holds its material in a wiped `SecureBuffer`, is `AutoCloseable`, and **always knows its matching public key** via the non-null `SigningKey.verifyKey` (see [verifyKey](#every-signing-key-knows-its-verifier)). Treat it as a one-shot resource and close it with `use {}`.

There are two ways to obtain a signing key.

### Generate a fresh key (preferred for new keys)

`generateSigningKey` mints a key from the platform CSPRNG and returns it **already carrying its `verifyKey`** — no separate public-key plumbing:

```kotlin
import com.ditchoom.buffer.crypto.*

suspend fun signAndVerify(message: ReadBuffer): Boolean =
    when (val s = CryptoCapabilities.signatures(SignatureScheme.Ed25519)) {
        is SignatureSupport.Blocking -> {
            val key = s.ops.generateSigningKeyBlocking()   // SyncCapableSigningKey
            key.use {
                val signature = s.ops.signBlocking(it, message)
                s.ops.verifyBlocking(it.verifyKey, message, signature)
            }
        }
        is SignatureSupport.AsyncOnly -> {
            val key = s.ops.generateSigningKey()           // suspend (web)
            key.use {
                val signature = s.ops.sign(it, message)
                s.ops.verify(it.verifyKey, message, signature)
            }
        }
        SignatureSupport.Unavailable -> error("scheme not available here")
    }
```

### Import an existing key

The import factories take the raw material **plus** its matching `VerifyKey` (which must be for the same scheme — mismatches throw):

```kotlin
// Ed25519: 32-byte raw private seed + 32-byte raw public key.
val verifyKey = VerifyKey.ed25519(publicKeyBuffer)
SigningKey.ed25519(seedBuffer, verifyKey).use { signingKey ->
    val s = CryptoCapabilities.signatures(SignatureScheme.Ed25519) as SignatureSupport.Blocking
    val signature = s.ops.signBlocking(signingKey, message)     // read-ready 64-byte signature
    val ok = s.ops.verifyBlocking(signingKey.verifyKey, message, signature)
}

// ECDSA P-256: 32-byte raw private scalar + uncompressed SEC1 point (04 ‖ X ‖ Y, 65 bytes).
val ecVerify = VerifyKey.ecdsaP256(uncompressedPointBuffer)
SigningKey.ecdsaP256(privateScalarBuffer, ecVerify).use { signingKey ->
    // …signBlocking / verifyBlocking as above…
}
```

`verifyBlocking`/`verify` return `true` only if the signature is valid; a tampered signature/message/key returns `false` — never accepted, never throw-as-valid. Malformed inputs (non-canonical DER, `r=0`, off-curve points) are rejected by the platform and also surface as `false`. Size a signature destination for `signInto` with `maxSignatureBytes(scheme)`.

:::note ECDSA encoding is per-platform (DER vs P1363)
JVM / Android / Apple / Linux produce and consume ASN.1 **DER** ECDSA signatures; JS/WASM (WebCrypto) produce raw fixed-width **P1363** `r ‖ s`. Discover it with `ecdsaSignatureEncoding`. Ed25519 has a single canonical 64-byte encoding everywhere. Both DER and P1363 vector files are run by the Wycheproof suite on the relevant targets.
:::

:::note ECDSA signing from a bare scalar
`SigningKey.ecdsaP256/384/521` derive the public point from the private scalar alone. This is supported on every current target — on Apple it is provided by CryptoKit — so `CryptoCapabilities.ecdsaSigningFromScalar` is `true` everywhere. The flag is retained for forward-looking feature detection. See the [capability matrix](#per-platform-capability-matrix).
:::

### Every signing key knows its verifier

`SigningKey.verifyKey` is a **non-null** member: an imported key carries the `VerifyKey` you supplied at construction, and a provider-minted hardware key carries the public key the secure element produced at generation. You never have to thread the public key alongside the private one — pass `signingKey.verifyKey` straight to `verify`.

## Key Agreement (ECDH / X25519)

Elliptic-curve key agreement covers X25519 (RFC 7748) and ECDH over P-256/P-384/P-521, reached through `CryptoCapabilities.keyAgreement(curve)`.

The **raw Diffie–Hellman output is never returned**. A raw shared secret is not a uniformly random key, so every entry point runs it through HKDF (extract-then-expand) keyed by the shared secret and domain-separated by a required `info` (and optional `salt`), returning the *derived* key material. The raw secret lives in a wiped `SecureBuffer` and is zeroed before return — even on the failure path.

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.toReadBuffer

suspend fun agree(): Boolean {
    val ka = CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519)
    require(ka is KeyAgreementSupport.Blocking || ka is KeyAgreementSupport.AsyncOnly)
    val ops = when (ka) {
        is KeyAgreementSupport.Blocking  -> ka.ops
        is KeyAgreementSupport.AsyncOnly -> ka.ops
        KeyAgreementSupport.Unavailable  -> error("curve unavailable")
    }

    // Each party generates a key pair from the platform CSPRNG.
    val alice = ops.generateKeyPair()
    val bob = ops.generateKeyPair()

    val info = Info.Of("my-app session key v1".toReadBuffer()) // domain separation (required)

    // Alice derives 32 bytes against Bob's public key; Bob derives the same 32 bytes against Alice's.
    val aliceKey = ops.deriveSharedSecret(alice.privateKey, bob.publicKey, info, length = 32)
    val bobKey = ops.deriveSharedSecret(bob.privateKey, alice.publicKey, info, length = 32)

    alice.close(); bob.close() // wipe the private keys
    return aliceKey.contentEquals(bobKey) // byte-identical
}
```

Peer public keys are validated before use: X25519 all-zero shared secrets (low-order points, RFC 7748 §6.1) and ECDH off-curve / infinity / small-subgroup points are rejected with `InvalidPublicKey`. To exchange or persist a **public** key, use its encoding (`KeyAgreementCurve.publicKeyBytes` long) and rebuild it with `KeyAgreementPublicKey.of(curve, encoded)`.

:::note Private-key serialization is the raw scalar on every platform
`importPrivateKey(curve, encoded)` and `KeyAgreementPrivateKey.exportEncoded()` use the **curve's raw big-endian scalar** (32/48/66 bytes for P-256/384/521, 32 bytes for X25519) on **every** platform, so a serialized private key is byte-portable across JVM/Android/Apple/Linux/JS/WASM. The platforms whose native stack needs a wrapped form reconstruct it just-in-time for the exchange and never surface it — Apple rebuilds the Security-framework X9.63 representation via CryptoKit, and JS/WASM wrap the scalar in PKCS#8 for WebCrypto. Public keys, shared secrets, signatures, and AEAD outputs are byte-identical across all platforms too.
:::

## HPKE (RFC 9180)

Hybrid Public Key Encryption is composed entirely over the primitives above (DHKEM over the key-agreement family, HKDF for the key schedule, and the AEAD layer). A suite is a `kem` + `kdf` + `aead` triple, and the whole API is `suspend`, so it covers the web. Operations live on the `HpkeSupport.Supported` witness; key construction is on the `Hpke` namespace.

```kotlin
import com.ditchoom.buffer.crypto.*
import com.ditchoom.buffer.toReadBuffer

val suite = HpkeSuite(
    kem = HpkeKem.DhkemX25519HkdfSha256,
    kdf = HpkeKdf.HkdfSha256,
    aead = HpkeAead.Aes128Gcm,
)

suspend fun singleShot() {
    val hpke = when (val h = CryptoCapabilities.hpke(suite)) {
        is HpkeSupport.Supported   -> h.ops
        is HpkeSupport.Unsupported -> error("suite needs: ${h.missing}")
    }

    // Recipient generates a long-term key pair; the public key is shared with senders.
    val recipient = Hpke.generateKeyPair(suite.kem)

    val info = Info.Of("app context".toReadBuffer())
    val aad = Aad.Of("v1".toReadBuffer())

    // Sender: seal to the recipient's public key → enc ‖ ciphertext.
    val sealed: HpkeSealed = hpke.sealBase(
        recipientPublicKey = recipient.publicKey,
        info = info,
        plaintext = "hello recipient".toReadBuffer(),
        aad = aad,
    )
    // Transmit sealed.enc (the encapsulated key) and sealed.ciphertext.

    // Recipient: open with the private key.
    val recovered = hpke.openBase(
        recipientPrivateKey = recipient.privateKey,
        enc = sealed.enc,
        info = info,
        ciphertext = sealed.ciphertext,
        aad = aad,
    )
    val message = recovered.readString(recovered.remaining())

    recipient.close() // wipes the private key
}
```

For a multi-message session, set up a context once and reuse it. The context owns a monotonic per-message sequence number — the nonce is `base_nonce XOR seq`, advanced only on success — so nonce reuse is impossible by construction, and `MessageLimitReached` is thrown before the counter could wrap:

```kotlin
val hpke = (CryptoCapabilities.hpke(suite) as HpkeSupport.Supported).ops

val sender = hpke.setupBaseSender(recipient.publicKey, info)   // HpkeSenderSetup(context, enc)
val ct1 = sender.context.seal("message one".toReadBuffer())
val ct2 = sender.context.seal("message two".toReadBuffer())

val receiver = hpke.setupBaseReceiver(recipient.privateKey, sender.enc, info) // HpkeContext.Receiver
val pt1 = receiver.open(ct1)
val pt2 = receiver.open(ct2)
```

All four RFC 9180 modes are exposed via the matching `ops` setup functions: `setupBaseSender`/`setupBaseReceiver` (Base), `setupPskSender`/`setupPskReceiver` (PSK, via `HpkePsk.of`), `setupAuthSender`/`setupAuthReceiver` (sender authentication), and `setupAuthPskSender`/`setupAuthPskReceiver` (both). A context can also derive independent secrets with `context.export(exporterContext, length)` (RFC 9180 §5.3).

Suite availability differs by platform — branch on the `HpkeSupport` witness (`Unsupported.missing` names the absent primitive). A suite is usable only if its KEM curve and AEAD are both available: a ChaCha20-Poly1305 suite is unsupported on the web, and an X25519 KEM requires Android API 34+ (see the matrix below).

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

The signing / key-agreement / HPKE import and generate factories default to `BufferFactory.deterministicSecure()`, so private material is already wiped-on-close without extra plumbing.

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

## Sync-capable keys (the hardware seam)

Two marker types make the synchronous-vs-hardware boundary a **compile-time** fact rather than a runtime throw:

- `SyncCapableAesGcmKey : AesGcmKey`
- `SyncCapableSigningKey : SigningKey`

Only in-memory keys — the ones `AesGcmKey.of` / `SigningKey.ed25519/ecdsaP*` / `generateSigningKey` return — implement these markers. The synchronous witness ops are bound to the marker type:

```kotlin
fun signInto(key: SyncCapableSigningKey, message: ReadBuffer, dest: WriteBuffer): Int
fun sealBlocking(key: SyncCapableAesGcmKey, plaintext: ReadBuffer, …): PlatformBuffer
```

A hardware-backed key (`KeyProvenance.Hardware`) holds no in-process material — it is an `AesGcmKey`/`SigningKey` but **not** sync-capable. So routing a hardware key into a blocking op does not compile; hardware keys operate only through the `suspend` witness ops, which dispatch to the secure element's gated closures. The `suspend` `seal`/`sign`/`open` ops still serve both kinds, so portable code that always uses the async path works with either provenance.

## Feature detection — branch on the witness

The witnesses are themselves the feature-detection surface. Resolve once, then dispatch:

```kotlin
import com.ditchoom.buffer.crypto.*

// AEAD: prefer the sync one-shot, fall back to the suspend path where only that exists (web).
suspend fun seal(key: SyncCapableAesGcmKey, pt: ReadBuffer): PlatformBuffer =
    when (val gcm = CryptoCapabilities.aesGcm) {
        is Aead.Blocking  -> gcm.ops.sealBlocking(key, pt)
        is Aead.AsyncOnly -> gcm.ops.seal(key, pt)
    }

// ChaCha20-Poly1305 is unavailable by *either* path on JS/WASM.
val chaChaUsable = CryptoCapabilities.chaChaPoly !is OptionalAead.Unavailable

// Signatures: a synchronous path exists only on a Blocking witness.
val ed25519Sync = CryptoCapabilities.signatures(SignatureScheme.Ed25519) is SignatureSupport.Blocking

// Key agreement: synchronous path on a Blocking witness.
val x25519Sync =
    CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519) is KeyAgreementSupport.Blocking

// ECDSA verify is universal; signing from a bare scalar is feature-detected (true on all current targets).
if (CryptoCapabilities.ecdsaSigningFromScalar) { /* sign from a raw private scalar */ }

// HPKE: check the whole suite (its KEM curve *and* AEAD must both be available).
when (val h = CryptoCapabilities.hpke(suite)) {
    is HpkeSupport.Supported   -> { /* h.ops */ }
    is HpkeSupport.Unsupported -> { /* h.missing names what's absent */ }
}

// Hardware-backed keys (secure element), if any.
when (val hw = CryptoCapabilities.hardware) {
    is HardwareSupport.Available   -> { /* hw.provider mints non-exportable keys */ }
    HardwareSupport.Unavailable    -> { /* JVM / Linux / JS / WASM */ }
}
```

The per-platform results of these checks are the [capability matrix](#per-platform-capability-matrix) below.

## Security model

The full threat model lives in [`buffer-crypto/SECURITY.md`](https://github.com/DitchOoM/buffer/blob/main/buffer-crypto/SECURITY.md). In brief:

- **Native-or-unrepresentable.** No primitive is hand-rolled. An unavailable algorithm has no reachable witness path (its `supports…` capability is `false`). (One documented exception: a pure-Kotlin SHA-2 / HMAC core on JS/WASM, because WebCrypto's `digest` is async-only and the library needs synchronous HKDF/HMAC — it is pinned by NIST/RFC known-answer vectors.)
- **Correctness** is proven by RFC/NIST known-answer vectors + Wycheproof, run on every target.
- **Opaque verify failures.** Every AEAD-tag / signature / authenticated-decrypt failure collapses to a single `VerificationFailed` with no reason or cause — a granular reason on a verify path is a decryption oracle. Misuse with non-secret detail surfaces as a structured `CryptoMisuseException` (e.g. `InvalidPublicKey`). No secret material ever appears in an exception, property, cause, or log.
- **Resource-exhaustion / DoS.** `BufferFactory.secure(maxAllocationBytes = …)` caps every secure allocation (16 MiB default backstop); HPKE's monotonic sequence prevents nonce reuse and throws `MessageLimitReached` before wrap; HKDF enforces the `L ≤ 255·HashLen` cap.
- **Out of scope (trust boundary):** side-channel / timing / fault resistance of the underlying native primitive, and the constant-time execution of the platform's own verify. The library's *own* secret-dependent comparisons are constant-time.
- **Supply-chain integrity.** Releases attach SLSA build provenance (`gh attestation verify <artifact> --repo DitchOoM/buffer`) and an attested CycloneDX SBOM. Third-party GitHub Actions are pinned to commit SHAs, tokens are least-privilege, CodeQL (`security-extended`) runs on every PR, and OpenSSF Scorecard runs weekly.

### Per-platform capability matrix

Capability flags are tested error paths, not assumptions — the witness drives a `Blocking`/`AsyncOnly`→works / `Unavailable`→unrepresentable test on every target.

| Primitive | JVM | Android (minSdk 28) | Apple | Linux | JS / WASM |
|---|---|---|---|---|---|
| SHA-256/384/512, HMAC, HKDF | ✅ | ✅ | ✅ | ✅ | ✅ (pure-Kotlin core) |
| AES-GCM 128/256 | ✅ | ✅ | ✅ | ✅ | ✅ (WebCrypto, async only) |
| ChaCha20-Poly1305 | ✅ (JDK 11+) | ✅ | ✅ | ✅ | ❌ unavailable — not in WebCrypto |
| ECDSA P-256/384/521 (verify) | ✅ | ✅ | ✅ | ✅ | ✅ |
| ECDSA signing | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ed25519 | ✅ (JDK 15+) | ❌ **all API levels** | ✅ | ✅ | ✅ newer engines (feature-detected) |
| X25519 | ✅ (JDK 11+) | ✅ **API 34+** (unavailable 28–33) | ✅ | ✅ | ✅ newer engines (feature-detected) |
| ECDH P-256/384/521 | ✅ | ✅ | ✅ | ✅ | ✅ |
| HPKE/DHKEM (P-256/384/521) | ✅ | ✅ | ✅ | ✅ | ✅ (WebCrypto async) |
| HPKE/DHKEM (X25519) | ✅ (JDK 11+) | ✅ **API 34+** | ✅ | ✅ | ✅ newer engines (feature-detected) |
| HPKE AEAD = ChaCha20-Poly1305 | ✅ (JDK 11+) | ✅ | ✅ | ✅ | ❌ unavailable — not in WebCrypto |
| Hardware-backed keys (secure element) | ❌ | ✅ AES-GCM + ECDSA-P256 (StrongBox / TEE) | ✅ ECDSA-P256 only (Secure Enclave) | ❌ | ❌ |

Legend: ✅ available · ❌ unavailable (no reachable witness path).

Notes:

- **Apple** primitives are provided by CryptoKit (via a Swift `@_cdecl` shim) alongside CommonCrypto and the Security framework. ChaCha20-Poly1305, Ed25519, X25519, and ECDSA signing from a raw scalar are all available — CryptoKit is above the module's deployment floors, so the witnesses are `Blocking` unconditionally. AES-GCM, ECDSA verify, and ECDH use CommonCrypto/Security. CryptoKit's Ed25519 signatures are randomized (not RFC-8032-deterministic), so they are validated by sign→verify rather than exact-byte vectors; RFC known-good verification still holds everywhere.
- **Linux** uses a native BoringSSL backend (statically linked) and has full byte-level parity with JVM across every primitive above, including a raw-scalar EC private-key encoding.
- **Android Ed25519 is unavailable on every API level.** Although Android 14 (API 34+) advertises Ed25519, the platform exposes it **only** through the `AndroidKeyStore` provider for keystore-resident keys — there is no general-purpose `Ed25519` `KeyFactory` that imports a caller-supplied raw 32-byte seed (verified on an API 36 device). A raw-key-import library therefore reports `SignatureSupport.Unavailable` for Ed25519 on Android. (A future hardware-backed, keystore-*generated* Ed25519 could be wired through the `HardwareKeyProvider` surface, but that is non-exportable generate-only and distinct from the import path.) **X25519** *is* genuinely API-gated: Conscrypt added `XDH` in Android 14, so X25519 / ECDH-X25519 and the X25519 HPKE KEM are available on API 34+ and `Unavailable` on 28–33.
- **JS/WASM** make ChaCha20-Poly1305 unavailable entirely (not in WebCrypto, never polyfilled), and feature-detect Ed25519/X25519 against the engine's WebCrypto at call time.
- **Hardware-backed keys** are reached through `CryptoCapabilities.hardware` (`HardwareSupport.Available` / `Unavailable`). The provider mints **non-exportable** keys inside the secure element; they carry `KeyProvenance.Hardware` and operate only through the `suspend` AEAD/signature witnesses (the blocking/material path does not compile for them), gated per-use by a `HardwareAuthorization`. **Android Keystore** backs AES-GCM and ECDSA-P256, StrongBox-backed when a dedicated secure element is present (`dedicatedSecureElement = true`) and TEE-backed otherwise. **Apple Secure Enclave** backs ECDSA-P256 only: CryptoKit exposes no app-controlled symmetric Enclave key, so AES-GCM is not eligible there. JVM, Linux, and JS/WASM wire no secure element, so `hardware` is `Unavailable`. A provider-minted signing key publishes its matching public key via `SigningKey.verifyKey`.

#### Encoding summary

| Material | JVM / Android | Apple | Linux | JS / WASM |
|---|---|---|---|---|
| ECDSA signature | DER | DER | DER | P1363 `r‖s` |
| ECDSA/ECDH public key | uncompressed SEC1 `04‖X‖Y` (all platforms) ||||
| Ed25519 / X25519 public key | raw 32-byte (all platforms) ||||
| EC/X25519 private key — `exportEncoded` | raw big-endian scalar, 32/48/66 (all platforms) ||||
| HPKE KEM keys | RFC 9180 raw encodings (all platforms) ||||

Public keys, shared secrets, **private-key serializations**, and AEAD outputs are byte-identical across platforms, so a `commonMain` caller gets consistent results and can move serialized keys between any targets. The private-key encoding is the raw scalar everywhere — Apple reconstructs the X9.63 form and JS/WASM a PKCS#8 wrapper just-in-time for their native stacks, never surfacing it. The **one** remaining per-platform wire difference is the ECDSA *signature* encoding (DER on JVM/Android/Apple/Linux, P1363 `r‖s` on JS/WASM, discoverable via `ecdsaSignatureEncoding`); a signature produced on one family must be transcoded before it verifies on the other.
