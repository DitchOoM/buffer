# Persistent, alias-addressable key lifecycle + SPKI export — design sketch

Status: **design signed off. Building.** Target: an additive minor on top of the non-exportable-keys
work (PR 289). This is a *fresh public surface*, not a reshape — nothing here changes an existing
signature.

## Decisions locked (do not relitigate)

1. `KeyStore : KeyProvider, AutoCloseable` — compose, reusing the whole PR-289 custody machinery
   (`custodyFor`/`eligible`/`requireTier`/`InsufficientKeyCustody`) verbatim. `KeyStore` is an
   ordinary (implementable) interface — a consumer may supply a wholesale custom store.
2. **All three families** persisted: signing + AES-GCM + key-agreement (mirrors `KeyProvider`).
3. **Persistence is two concerns.** Custody backend (who holds/operates the key) and persistence
   medium (where durable bytes/refs live) **fuse** on hardware / non-exportable-software tiers
   (Android Keystore, Apple Keychain, WebCrypto-IndexedDB) — the OS is *both* custodian and durable
   store, there is no exportable blob, so persistence there is **not pluggable by design**. They
   **separate** on the exportable-software tier (JVM/Linux), where the key is a PKCS#8 DER blob and
   its medium is consumer policy. So: ship a minimal `KeyStorage` blob-SPI that swaps just the
   byte-sink under the **software** store (keeps all DER/idempotency logic); OS-backed tiers ignore it.
4. `VerifyKey.exportEncoded` + `exportSpki`; **also** `KeyAgreementPublicKey.exportSpki` (symmetry).
5. **Enforce a portable alias charset** `[A-Za-z0-9._-]{1,255}` (predictable across FS / Keychain / IDB).
6. **Regenerate = `delete(alias)` + `getOrGenerate*`** idiom (no `regenerate*` convenience methods) —
   honest about the fact that no platform offers atomic replace.
7. Build scope this session: common surface + JVM on-disk + web IndexedDB (green on `jvmTest` +
   `jsNodeTest` on WSL). Android Keystore + Apple Keychain actuals are CI-gated follow-up commits.

Builds directly on the custody model already shipped (`KeyCustody`, `KeyProvider`/`ConcreteKeyProvider`
/`ProtectedKeyProvider`/`HardwareKeyProvider`, `keyProvider()`, `custodyFor`/`requireTier`). See
`NON_EXPORTABLE_KEYS_DESIGN.md`.

## Problem

buffer-crypto today only **mints ephemeral, in-memory key handles**: `keyProvider().generateSigning /
generateAesGcm / generateKeyAgreement` return a handle whose alias is an internal random detail,
auto-deleted on `close()`. A device-identity consumer needs the opposite lifecycle — a **named key
that survives process restarts** — and today has to hand-roll it per platform (Android Keystore
alias, Apple Keychain tag, WebCrypto `CryptoKey` in IndexedDB, JVM on-disk DER). Two concrete gaps:

1. **No persistent, alias-addressable lifecycle** — get-or-generate-by-alias (idempotent),
   load-by-alias across launches, delete/regenerate-by-alias.
2. **No SPKI public-key export** — `VerifyKey` is opaque (it exposes only `scheme`/`provenance`); a
   consumer cannot even get the raw point out, let alone the X.509 `SubjectPublicKeyInfo` DER a
   device-identity registration flow publishes. `Sign→P1363` is already covered.

Both are purely **additive**. Gap 2 is small and unblocks device-identity *publication*; gap 1 is the
larger surface.

---

## Part B — SPKI (and raw) public-key export off `VerifyKey`  *(small, do first)*

The DER encoders **already exist** as public commonMain functions in `EcInterop.kt`:
`ecPublicKeyToSpki(curve, point)`, `ed25519PublicKeyToSpki(rawPublicKey)`,
`x25519PublicKeyToSpki(rawPublicKey)`. The only missing piece is a way to get the public bytes *out of
a `VerifyKey`* and a one-call bridge to SPKI. Mirror the naming already used by
`KeyAgreementPublicKey.exportEncoded(...)`.

```kotlin
sealed interface VerifyKey {
    val scheme: SignatureScheme
    val provenance: KeyProvenance

    /**
     * The public key in its **standard raw import encoding**, read-ready, allocated from [factory]:
     *  - Ed25519 → the 32-byte raw public key (RFC 8032 §5.1.5),
     *  - ECDSA P-256/384/521 → the uncompressed SEC1 point `0x04 ‖ X ‖ Y`.
     * This is the exact encoding the `VerifyKey.ecdsaP256(...)` / `ed25519(...)` factories consume,
     * so `VerifyKey.of(scheme, k.exportEncoded())` round-trips.
     */
    fun exportEncoded(factory: BufferFactory = BufferFactory.Default): ReadBuffer

    /**
     * The public key as **X.509 SubjectPublicKeyInfo DER** (RFC 5280 for ECDSA, RFC 8410 for
     * Ed25519) — the form certificates, JWK-to-DER, `openssl`, and a device-identity registration
     * endpoint consume. Read-ready, allocated from [factory].
     */
    fun exportSpki(factory: BufferFactory = BufferFactory.Default): ReadBuffer
}
```

Implementation is pure commonMain on the sealed interface — every `VerifyKey` is an
`InMemoryVerifyKey` (its `requireInMemoryMaterial()` is the material buffer), including the one a
`ProtectedSigningKey` (WebCrypto / secure element) captures at generation, so this works uniformly
across all custody tiers with **no per-platform code**:

```kotlin
// exportEncoded → copyBuffer(requireInMemoryMaterial(), factory)
// exportSpki → when (scheme) {
//     Ed25519   -> ed25519PublicKeyToSpki(material, factory)
//     EcdsaP256 -> ecPublicKeyToSpki(KeyAgreementCurve.P256, material, factory)
//     EcdsaP384 -> ecPublicKeyToSpki(KeyAgreementCurve.P384, material, factory)
//     EcdsaP521 -> ecPublicKeyToSpki(KeyAgreementCurve.P521, material, factory)
// }
```

Device-identity publication then reads: `signingKey.verifyKey.exportSpki()`.

**Symmetry (proposed, cheap):** add the same `exportSpki(...)` to `KeyAgreementPublicKey` (which
already has `exportEncoded`) — the EC/x25519 SPKI helpers exist, so it is a 4-line dispatch. Flagged
as an open decision, not assumed.

---

## Part A — persistent, alias-addressable keystore

### Shape: `KeyStore` **is a** `KeyProvider`

A keystore is a `KeyProvider` (it mints keys) *plus* a named, persistent lifecycle. Making
`KeyStore : KeyProvider` means the **entire custody-tier machinery from PR 289 applies to persistent
keys for free** — `custodyFor(alg)`, `eligible(alg)`, `requireTier(alg, tier)`, `InsufficientKeyCustody`
all work on a `KeyStore` unchanged. The inherited *ephemeral* `generate*` methods still mint
throwaway keys (auto-deleted on close); the new alias methods persist.

```kotlin
/**
 * A persistent, alias-addressable key store. Unlike the ephemeral [KeyProvider.generateSigning] et al.
 * (random alias, auto-deleted on close), a key obtained through an alias method here **persists** —
 * it survives close() and process restart — until [delete]d. Custody is per platform and reported
 * through the inherited [KeyProvider.custodyFor]: on-disk DER is [KeyCustody.ExportableSoftware] (JVM/
 * Linux), a non-extractable WebCrypto CryptoKey in IndexedDB is [KeyCustody.NonExportable.Software]
 * (JS/WASM), a keystore alias / Keychain tag is [KeyCustody.NonExportable.Hardware] (Android/Apple).
 */
interface KeyStore : KeyProvider, AutoCloseable {
    /**
     * Idempotent get-or-generate. If [alias] already holds a signing key **of [scheme]**, re-attaches
     * to it; if it holds a key of a different kind or scheme, throws
     * [KeyStoreException.AliasMismatch]; otherwise generates a fresh signing key, persists it under
     * [alias], and returns it. Custody follows the platform ([custodyFor]).
     */
    suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): SigningKey

    suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): AesGcmKey

    suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): KeyAgreementKeyPair

    /**
     * Re-attach to a key persisted in a previous launch. Returns `null` if [alias] is absent **or**
     * holds a key of a different kind (a signing lookup over an AES alias is `null`, not an error).
     */
    suspend fun loadSigning(alias: String): SigningKey?
    suspend fun loadAesGcm(alias: String): AesGcmKey?
    suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair?

    /** Whether any key is stored under [alias]. */
    suspend fun contains(alias: String): Boolean

    /** Every alias currently stored (namespaced to this store's [KeyStoreConfig.name]). */
    suspend fun aliases(): Set<String>

    /** Deletes the key under [alias]. Returns `true` if a key was removed, `false` if none existed. */
    suspend fun delete(alias: String): Boolean
}
```

**Regenerate** is `delete(alias)` followed by `getOrGenerate*` — an honest two-call idiom (no platform
offers atomic replace). Documented as a pattern; **open decision** whether to also ship a convenience
`regenerateSigning(...)` etc.

### Alias↔algorithm binding is intrinsic, no sidecar metadata

Each backend already records the algorithm, so there is **no separate metadata format to design**:
on-disk PKCS#8 carries the `AlgorithmIdentifier` OID; an IndexedDB `CryptoKey` carries `.algorithm`;
a keystore/Keychain entry carries its key type. `getOrGenerate*` reads that to decide match / mismatch
/ generate; `load*` reads it to decide kind.

### Construction: one total, per-platform-resolving accessor

Mirror the `platformProtectedKeyProvider()` seam: an **internal** `expect` builds the platform store,
a stable commonMain `fun` is the public entry point. Every platform *can* persist (JVM/Linux on-disk
always works), so — unlike `protectedKeys` — this is **total and non-null**, like `keyProvider()`.

```kotlin
/**
 * Per-platform configuration. Carried by value; platforms cherry-pick the fields they use.
 */
class KeyStoreConfig(
    /**
     * Namespace for this store: the IndexedDB database name (web), the Keychain service /
     * `kSecAttrService` (Apple), and an alias prefix (Android Keystore, JVM on-disk). Lets a consumer
     * keep independent stores side by side.
     */
    val name: String = "buffer-crypto",
    /**
     * Software-tier persistence medium (JVM / Linux only). `null` → the platform default on-disk
     * store (`<user.home>/.buffer-crypto/<name>`, created 0700). Supply a [KeyStorage] to redirect
     * exportable PKCS#8 into your own store (encrypted DB, secret manager). **Ignored on OS-backed
     * tiers** (Android / Apple / web), whose keys never become an exportable blob.
     */
    val storage: KeyStorage? = null,
)

/**
 * The pluggable persistence medium under a *software (exportable)* [KeyStore]. Sees only opaque,
 * already-encoded PKCS#8 bytes under a caller-chosen alias — never key semantics — so it can be any
 * durable named-blob store. `put`'s bytes are **secret** private-key material: do not log; wipe on
 * delete. Ignored on OS-backed tiers (no exportable blob exists there).
 */
interface KeyStorage {
    suspend fun put(alias: String, pkcs8: ReadBuffer)
    suspend fun get(alias: String): ReadBuffer?
    suspend fun delete(alias: String): Boolean
    suspend fun aliases(): Set<String>
}

// Internal seam (kept out of the public ABI), one per platform. Reads config.storage on the
// software tier; ignores it on OS-backed tiers.
internal expect fun platformKeyStore(config: KeyStoreConfig): KeyStore

/**
 * The device-persistent key store for this platform. Total and non-null — every platform can persist.
 * Inspect the custody you actually got with [KeyProvider.custodyFor] / assert it with
 * [KeyProvider.requireTier]: a consumer that requires hardware custody for its identity key writes
 * `keyStore().requireTier(EcdsaP256, CustodyTier.Hardware).getOrGenerateSigning("device-identity", …)`
 * and it fails loudly on JVM/web instead of silently persisting a weaker key.
 */
fun CryptoCapabilities.keyStore(config: KeyStoreConfig = KeyStoreConfig()): KeyStore =
    platformKeyStore(config)
```

### Persistence & `close()` semantics (the critical difference from ephemeral)

- An **ephemeral** key from the inherited `generate*` keeps its existing behaviour: `close()` releases
  the handle and (for a provider that assigned a scratch alias) deletes it.
- A **persistent** key from `getOrGenerate*` / `load*` has `onClose = {}` — `close()` releases only the
  in-process handle; **the stored key survives**. Removal is *always explicit* via `store.delete(alias)`.
  This is the invariant a device-identity key depends on, so it is stated in the SPI conformance
  obligations and asserted by the conformance test.

### Errors

One new sealed family, consistent with the existing `CryptoMisuseException` tree (internal
constructors, typed not string-discriminated):

```kotlin
sealed class KeyStoreException private constructor(message: String) : CryptoMisuseException(message) {
    /** [alias] exists but holds a different [ProtectedKeyAlgorithm] than the get-or-generate requested. */
    class AliasMismatch internal constructor(
        val alias: String,          // an alias is caller-chosen and non-secret
        val stored: ProtectedKeyAlgorithm,
        val requested: ProtectedKeyAlgorithm,
    ) : KeyStoreException("alias '$alias' holds $stored, not the requested $requested")

    /** The backing store could not be reached (disk IO, keystore access). [retryable] mirrors the platform. */
    class StorageFailure internal constructor(
        val retryable: Boolean,
    ) : KeyStoreException("key store backend unavailable")
}
```

No secret material appears in any field (aliases are caller-chosen public names) — same invariant the
rest of the hierarchy holds.

### Per-platform backing (implementation)

| Platform | Custody | Backing | Persistence unit |
|----------|---------|---------|------------------|
| JVM / Linux | `ExportableSoftware` | PKCS#8 DER on disk under `location`, 0700, filename = hash(alias) | the `.der` file |
| JS / WASM | `NonExportable.Software` | `subtle.generateKey(..., extractable=false, ...)` → the **non-extractable `CryptoKey`** stored in **IndexedDB** (structured-clone preserves it; never `localStorage`, never extractable) | the IDB record |
| Android | `NonExportable.Hardware` | `AndroidKeyStore` entry keyed by `<name>:<alias>` | the keystore alias |
| Apple | `NonExportable.Hardware` | Keychain item, `kSecAttrApplicationTag = alias`, `kSecAttrService = name`, `kSecAttrTokenIDSecureEnclave` where eligible | the Keychain item |

The reloaded handle is the ordinary sealed type: JVM parses PKCS#8 → `InMemorySigningKey`; web/Android/
Apple wrap the non-exportable handle → `ProtectedSigningKey`/`ProtectedAesGcmKey` with gated closures
and `onClose = {}`. So a persistent key flows through the exact same witnesses
(`signatures()`/`aesGcm()`/`keyAgreement()`/`hpke()`) as every other key — "persistent" is not a
special case the consumer branches on.

### SPI conformance obligations (normative, for each platform actual)

1. `getOrGenerate*` is **idempotent**: a second call with the same alias+algorithm returns a key
   equivalent to the first (same public key / same stored secret), never a new one.
2. A persistent key's `close()` **must not** delete the stored key; only `delete(alias)` removes it.
3. `getOrGenerate*` throws `AliasMismatch` (never silently replaces) when the alias holds a different
   kind/scheme — a device-identity key must never be clobbered by a mistyped call.
4. Custody reported by `custodyFor(alg)` **must** equal the custody of the keys actually persisted.
5. Backend-access failure surfaces as `KeyStoreException.StorageFailure`, never a raw platform
   exception leaking through.

---

## Compatibility & ABI

- **Additive only.** `VerifyKey` gains two members — it is a `sealed interface` with a single
  module-internal impl (`InMemoryVerifyKey`), so adding members is binary-compatible (no external
  implementers). `KeyStore`, `KeyStoreConfig`, `keyStore()`, `KeyStoreException`, and the two
  `VerifyKey` methods are all new declarations.
- `KeyStore : KeyProvider` reuses `custodyFor`/`eligible`/`requireTier`/`InsufficientKeyCustody`
  verbatim — no new custody surface.
- Internal `platformKeyStore(config)` seam stays out of the public ABI (like
  `platformProtectedKeyProvider()`).
- New public API must be added to `api/*/buffer-crypto.api` in the same change.

## Test strategy

- **commonTest fake** `InMemoryKeyStore` (a `MutableMap<String, StoredEntry>`) + `platformKeyStore`
  overridable in tests, driving a `KeyStoreConformanceTest`: idempotency, load-after-"restart",
  delete, `AliasMismatch`, persistent `close()` does not delete, custody equals `custodyFor`,
  wrapper transparency.
- **JVM** (`:buffer-crypto:jvmTest`): real on-disk round-trip — generate under a temp dir, drop the
  store instance, open a *new* store over the same dir, `loadSigning` returns the same public key;
  `exportSpki()` parses back via `spkiToEcPublicKey` / equals `ecPublicKeyToSpki(exportEncoded())`.
- **Web** (`:buffer-crypto:jsNodeTest`): IndexedDB round-trip (fake-indexeddb under node), key stays
  `extractable:false`, sign→verify after reload, HPKE decap with a reloaded non-exportable agreement
  key.
- Apple/Android: conformance is the same commonTest suite; the real Keychain/Keystore actuals are
  verified on CI (Mac / instrumented) — cannot compile Apple or run instrumented tests on WSL.

## Build order (independent, green-able commits)

1. **SPKI/raw export (Part B).** Add `VerifyKey.exportEncoded` + `exportSpki` (+ optionally
   `KeyAgreementPublicKey.exportSpki`). Pure commonMain over existing `EcInterop` encoders. Tests +
   ABI. Small, self-contained, immediately verifiable on JVM.
2. **`KeyStore` common surface + fake.** `KeyStore` interface, `KeyStoreConfig`, `KeyStoreException`,
   `keyStore()` + internal `platformKeyStore` seam, commonTest `InMemoryKeyStore` +
   `KeyStoreConformanceTest`. Non-JVM/JS platforms initially delegate to an in-memory/default actual
   so the module stays green everywhere.
3. **JVM on-disk actual.** PKCS#8 DER persistence under `location`, `ExportableSoftware`. jvmTest
   restart round-trip.
4. **Web IndexedDB actual.** Non-extractable `CryptoKey` in IDB, `NonExportable.Software`. jsNodeTest.
5. **Android Keystore + Apple Keychain actuals.** CI-gated (instrumented / Mac). Separate commits.
6. **api/*.api regen + SECURITY.md note** (persistence tier + platform matrix).

Every commit message / PR generic and feature-framed; never reference any downstream consumer.

## Verify targets

`:buffer-crypto:jvmTest :buffer-crypto:apiCheck :buffer-crypto:ktlintCheck :buffer-crypto:jsNodeTest`
(regen ABI via `:buffer-crypto:apiDump`). NOT `:buffer-crypto:check` (unrelated `:buffer:lintAnalyzeDebug`
crash). Apple leg on CI.

## Open decisions (need sign-off)

1. **`KeyStore : KeyProvider`** (compose, reuse custody machinery) vs a standalone `KeyStore`.
   *Recommend compose.*
2. **v1 algorithm scope**: all three families (signing + AES-GCM + key-agreement) vs signing-only
   first. *Recommend all three* (symmetry with `KeyProvider`; a consumer likely needs a persistent
   agreement key for HPKE session setup too).
3. **`regenerate*` convenience** methods vs the `delete` + `getOrGenerate` idiom. *Recommend the idiom*
   (honest about non-atomicity), documented.
4. **`KeyAgreementPublicKey.exportSpki`** included in Part B or deferred. *Recommend include* (cheap,
   symmetric).
5. **Alias charset**: enforce a portable subset (`[A-Za-z0-9._-]{1,255}`) vs accept any non-blank
   string and escape internally. *Recommend enforce* (predictable across filesystem / Keychain / IDB).
</content>
</invoke>
