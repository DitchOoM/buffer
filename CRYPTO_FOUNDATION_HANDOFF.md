# Crypto primitives — foundation handoff (2026-06-17)

Foundation for the 6.0 crypto-primitive expansion is **landed on this trunk**. This doc is
the self-contained brief for the fan-out work. (Background/decisions also in the session
memory `crypto-v6-expansion.md`.)

## Naming guardrail (HARD)
**Never** mention MLS / RFC 9420 / "message layer security" in any commit, doc, PR title/body,
or public API. Name everything after the algorithm (Ed25519, AES-GCM, HPKE, …). The protocol
suites are only an internal checklist for *which* primitives to cover.

## Current state
- **Trunk:** `redesign/v6-rel` (the 6.0 integration branch), checked out in worktree
  `/home/rbehera/git/buffer-v6`. **Local only — not pushed.**
- `0e0853dc` merge: crypto foundation (`:buffer-crypto` = SHA-256/HMAC/HKDF/CSPRNG +
  SecureBuffer) + codec/buffer work (#188–#195) merged onto the v6 byte-layer reshape. Clean.
- `db12fffc` feat: `ReadBuffer.constantTimeEquals` (shared security util) + tests (JVM/JS/WASM green).
- This machine builds **JVM / Android / JS / WASM** only. **Apple** actuals are written to the
  CommonCrypto/CryptoKit pattern and verified on **Mac CI**. **Linux is deferred.**

## Scope (practical superset)
| Family | Add | Notes |
|---|---|---|
| Hashes | SHA-384, SHA-512 | + HMAC + HKDF over them |
| AEAD | AES-GCM 128/256, ChaCha20-Poly1305 | ChaCha throws on web (not in WebCrypto) |
| Signatures | Ed25519, ECDSA P-256/384/521 | Ed448 deferred |
| Key agreement | X25519, ECDH P-256/384/521 | X448 deferred |
| Composition | HPKE / DHKEM (RFC 9180) over X25519 + P-curves | needs AEAD + key-agreement |

`cryptography-kotlin` (whyoleg, Apache-2.0) is **reference only — do NOT depend on it.** Wrap each
platform's native stack: JCA (JVM/Android), CryptoKit/CommonCrypto (Apple), WebCrypto (js/wasmJs).

## PR decomposition (foundation done → fan-out → compose)
Foundation locks the file layout so primitive families live in **disjoint files** → parallel PRs
don't conflict (only `build.gradle.kts` is shared, and reference-only = no new deps, so it's rarely touched).

```
crypto/hashes-sha384-512   ┐
crypto/aead                ├─ independent, parallel, each its own PR into redesign/v6-rel
crypto/signatures          │
crypto/key-agreement       ┘
crypto/hpke                ── composes last (needs aead + key-agreement)
```
File layout per family (commonMain + per-platform actuals):
```
commonMain/.../crypto/{Aead,Signatures,KeyAgreement}.kt
{jvmCommon,apple,jsAndWasmJs}Main/.../crypto/{Aead,Signatures,KeyAgreement}.<plat>.kt
```
Each family owns its OWN capability flag file (do NOT centralize flags — that re-introduces conflicts).

## API shape — mirror `:buffer-compression`
- **Sync** top-level `expect fun` for native platforms; `expect val supports<X>: Boolean` capability flag.
- **Async** `suspend fun <x>Async(...)` wrapper that works everywhere incl. browser.
- Buffer-typed throughout (`ReadBuffer`/`WriteBuffer`/`BufferFactory`); **no ByteArray** (see existing module).
- Unsupported platform/algorithm → **throw `UnsupportedOperationException`** + flag `false`.

Template (match `buffer-compression/.../Compression.kt`):
```kotlin
expect fun seal(key: AeadKey, nonce: ReadBuffer, aad: ReadBuffer?, plaintext: ReadBuffer, dest: WriteBuffer)
expect val supportsSyncAesGcm: Boolean
suspend fun sealAsync(key: AeadKey, plaintext: ReadBuffer, aad: ReadBuffer? = null,
                      factory: BufferFactory = BufferFactory.Default): ReadBuffer
```

## Security gates — bake into EVERY crypto PR (user: "never add open attack vectors, ever")
1. **Constant-time** compare for all secret-dependent checks — use `ReadBuffer.constantTimeEquals`
   (foundation). Never use `contentEquals`/`mismatch` on secrets (they short-circuit = timing oracle).
2. **AEAD releases plaintext only after tag verification** — native one-shot; no unverified-plaintext streaming. Decrypt failure throws.
3. **Nonce safety** — default to library-generated CSPRNG nonces (no accidental reuse); explicit-nonce only as an advanced, loudly-documented overload.
4. **Public-key validation** for ECDH/X25519 — reject low-order / invalid points (small-subgroup attacks).
5. **Misuse-resistant typed keys** (e.g. `SigningKey` vs `VerifyKey` vs `AeadKey`) so keys can't be cross-used.
6. Secrets (private keys, shared secrets, derived material) in `SecureBuffer`, wiped on close. No secrets in logs/exception messages. CSPRNG only. No hand-rolled primitives (native-or-throw).
7. **`/security-review` must run clean before merge.**

## Cross-platform consistency tests — ONE `commonTest` suite, runs on EVERY target (incl Apple via CI)
- **Known-answer vectors**: RFC/NIST + **Wycheproof** (Google) for ECDSA/X25519/AES-GCM. Every platform
  matching the same KAT ⇒ transitive cross-platform consistency (a tag/sig from one platform verifies on another).
- **Round-trip** (sign→verify, seal→open) per platform.
- **Negative tests**: tampered ciphertext/AAD/tag/signature **MUST throw** (never silently pass); truncated/oversized inputs.
- **Capability-aware**: same test source asserts correctness where the flag is `true`, and
  `assertFailsWith<UnsupportedOperationException>` where `false` — so the unsupported contract is consistent too.
- Build inputs via `CryptoTestVectors.hexBuffer(...)` (no ByteArray). See existing `CryptoTestVectors.kt`.

## Per-platform support (near-term; everything else throws-for-now)
| Primitive | JVM | Android (minSdk 28) | Apple | Web (async) |
|---|---|---|---|---|
| SHA-384/512, HMAC, HKDF | ✓ | ✓ | ✓ | ✓ |
| AES-GCM 128/256 | ✓ | ✓ | ✓ | ✓ |
| ChaCha20-Poly1305 | ✓ (JDK 11+) | ✓ | ✓ | ✗ throws |
| ECDSA / ECDH P-256/384/521 | ✓ | ✓ | ✓ | ✓ |
| Ed25519 / X25519 | ✓ (JDK 15+/12+) | ✓ **API 34+** (runtime `SDK_INT` gate, throws below) | ✓ | ✓ (newer browsers) |
| HPKE | composed | composed | composed | composed |
Linux: deferred. When it returns, use **BoringSSL** (not OpenSSL) like `../socket`
(`socket/libs/boringssl/...` + cinterop def `socket-quic-quiche/.../BoringSslX509.def`).

## Fan-out execution
- Per family: `git worktree add ../buffer-<family> -b crypto/<family> redesign/v6-rel`, one background agent each.
- **Definition of done** per PR: KAT + round-trip + negative + capability tests green on JVM+JS+WASM here;
  Apple actuals written to pattern (Mac-CI verified); ktlint clean; `/security-review` clean; no MLS naming.

## Build / test commands (run from the worktree)
```
./gradlew :buffer-crypto:jvmTest :buffer-crypto:jsNodeTest :buffer-crypto:wasmJsNodeTest
./gradlew :buffer-crypto:ktlintCheck      # ktlintFormat to auto-fix
```

## Open follow-ups
- Push `redesign/v6-rel` (foundation merge + scaffolding) when ready — currently local only.
- Publishing 6.0 / `6.0.0-SNAPSHOT` for downstream CI is tracked separately (see HANDOFF_V6.md in the
  main worktree) and is independent of the primitive fan-out.
