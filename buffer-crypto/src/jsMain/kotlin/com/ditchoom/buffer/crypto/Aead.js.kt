package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * JS (browser + Node) WebCrypto AES-GCM bridge.
 *
 * Hex strings cross the Kotlin/JS boundary; the JS shim converts them to `Uint8Array`s, calls
 * `crypto.subtle.encrypt` / `decrypt` with `{ name: "AES-GCM", iv, additionalData, tagLength: 128 }`,
 * and returns lowercase hex. Decrypt resolves to `null` when WebCrypto throws (bad tag), which
 * the common layer maps to the opaque [VerificationFailed]. Node 20+ exposes `globalThis.crypto`.
 */

internal actual suspend fun webCryptoAesGcmEncrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String = jsAesGcmEncrypt(keyHex, ivHex, aadHex, plaintextHex).await()

internal actual suspend fun webCryptoAesGcmDecrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String? = jsAesGcmDecrypt(keyHex, ivHex, aadHex, ciphertextAndTagHex).await()

// Promise chains (not async/await): the Kotlin/JS `js(...)` intrinsic rejects async statements.
private fun jsAesGcmEncrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    ptHex: String,
): Promise<String> =
    js(
        """
        (function () {
            const subtle = (globalThis.crypto).subtle;
            const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
            const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
            const params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
            if (aadHex.length > 0) params.additionalData = h2b(aadHex);
            return subtle.importKey('raw', h2b(keyHex), { name: 'AES-GCM' }, false, ['encrypt'])
                .then((key) => subtle.encrypt(params, key, h2b(ptHex)))
                .then((ct) => b2h(ct));
        })()
        """,
    )

private fun jsAesGcmDecrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    ctHex: String,
): Promise<String?> =
    js(
        """
        (function () {
            const subtle = (globalThis.crypto).subtle;
            const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
            const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
            const params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
            if (aadHex.length > 0) params.additionalData = h2b(aadHex);
            return subtle.importKey('raw', h2b(keyHex), { name: 'AES-GCM' }, false, ['decrypt'])
                .then((key) => subtle.decrypt(params, key, h2b(ctHex)))
                .then((pt) => b2h(pt))
                .catch((e) => null);
        })()
        """,
    )
