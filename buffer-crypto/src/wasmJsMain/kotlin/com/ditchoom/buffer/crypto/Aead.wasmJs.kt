@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * WasmJs WebCrypto AES-GCM bridge.
 *
 * Same contract as the JS actual, but marshalled through `JsString` / `Promise<JsString?>`
 * externals (the Wasm interop encoding). The JS shim converts hex to `Uint8Array`, calls
 * `crypto.subtle.encrypt` / `decrypt` with `AES-GCM` + `tagLength: 128`, and returns lowercase
 * hex; decrypt resolves to `null` on a bad tag, which the common layer turns into the opaque
 * [VerificationFailed].
 */

internal actual suspend fun webCryptoAesGcmEncrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String =
    jsAesGcmEncrypt(keyHex.toJsString(), ivHex.toJsString(), aadHex.toJsString(), plaintextHex.toJsString())
        .await<JsString>()
        .toString()

internal actual suspend fun webCryptoAesGcmDecrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String? =
    jsAesGcmDecrypt(keyHex.toJsString(), ivHex.toJsString(), aadHex.toJsString(), ciphertextAndTagHex.toJsString())
        .await<JsString?>()
        ?.toString()

@JsFun(
    """
    (keyHex, ivHex, aadHex, ptHex) => (async () => {
        const subtle = (globalThis.crypto).subtle;
        const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
        const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
        const key = await subtle.importKey('raw', h2b(keyHex), { name: 'AES-GCM' }, false, ['encrypt']);
        const params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
        if (aadHex.length > 0) params.additionalData = h2b(aadHex);
        const ct = await subtle.encrypt(params, key, h2b(ptHex));
        return b2h(ct);
    })()
    """,
)
private external fun jsAesGcmEncrypt(
    keyHex: JsString,
    ivHex: JsString,
    aadHex: JsString,
    ptHex: JsString,
): Promise<JsString>

@JsFun(
    """
    (keyHex, ivHex, aadHex, ctHex) => (async () => {
        const subtle = (globalThis.crypto).subtle;
        const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
        const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
        const key = await subtle.importKey('raw', h2b(keyHex), { name: 'AES-GCM' }, false, ['decrypt']);
        const params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
        if (aadHex.length > 0) params.additionalData = h2b(aadHex);
        try {
            const pt = await subtle.decrypt(params, key, h2b(ctHex));
            return b2h(pt);
        } catch (e) {
            return null;
        }
    })()
    """,
)
private external fun jsAesGcmDecrypt(
    keyHex: JsString,
    ivHex: JsString,
    aadHex: JsString,
    ctHex: JsString,
): Promise<JsString?>
