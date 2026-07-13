@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * wasmJs WebCrypto glue for the non-exportable software key provider. Mirrors the JS actual but
 * marshals strings through `JsString` and uses `@JsFun` externals; non-extractable `CryptoKey`s live
 * in the same `globalThis.__bcNE` registry, referenced by an integer token. Each `@JsFun` body is
 * self-contained (the annotation value must be a constant), so the registry + hex helpers are inlined.
 */

internal actual val subtleGenerateKeyAvailable: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    jsSubtleGenerateKeyAvailable()
}

internal actual suspend fun webCryptoGenerateNonExportableEcdsa(curveName: String): String =
    jsGenerateEcdsa(curveName.toJsString()).await<JsString>().toString()

internal actual suspend fun webCryptoEcdsaSign(
    token: Int,
    hashName: String,
    messageHex: String,
): String = jsEcdsaSign(token, hashName.toJsString(), messageHex.toJsString()).await<JsString>().toString()

internal actual suspend fun webCryptoGenerateNonExportableAesGcm(lengthBits: Int): String =
    jsGenerateAesGcm(lengthBits).await<JsString>().toString()

internal actual suspend fun webCryptoAesGcmSealHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String =
    jsAesGcmSeal(token, ivHex.toJsString(), aadHex.toJsString(), plaintextHex.toJsString())
        .await<JsString>()
        .toString()

internal actual suspend fun webCryptoAesGcmOpenHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String? =
    jsAesGcmOpen(token, ivHex.toJsString(), aadHex.toJsString(), ciphertextAndTagHex.toJsString())
        .await<JsString?>()
        ?.toString()

internal actual suspend fun webCryptoGenerateNonExportableEcdh(curveName: String): String =
    jsGenerateEcdh(curveName.toJsString()).await<JsString>().toString()

internal actual suspend fun webCryptoEcdhDeriveBits(
    token: Int,
    curveName: String,
    peerPublicHex: String,
    bits: Int,
): String =
    jsEcdhDeriveBits(token, curveName.toJsString(), peerPublicHex.toJsString(), bits)
        .await<JsString>()
        .toString()

internal actual fun webCryptoReleaseHandle(token: Int) {
    jsReleaseHandle(token)
}

// --- externals ---------------------------------------------------------------

@JsFun(
    """() => {
      try {
        var s = (globalThis.crypto && globalThis.crypto.subtle);
        return !!(s && typeof s.generateKey === 'function');
      } catch (e) { return false; }
    }""",
)
private external fun jsSubtleGenerateKeyAvailable(): Boolean

@JsFun(
    """(curveName) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var kp = await subtle.generateKey({ name: 'ECDSA', namedCurve: curveName }, false, ['sign']);
      var rawPub = await subtle.exportKey('raw', kp.publicKey);
      var t = reg.n++; reg.m.set(t, kp.privateKey);
      return String(t) + ':' + hx(rawPub);
    })()""",
)
private external fun jsGenerateEcdsa(curveName: JsString): Promise<JsString>

@JsFun(
    """(token, hashName, messageHex) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function h2b(h) { var a = new Uint8Array(h.length / 2); for (var i = 0; i < a.length; i++) { a[i] = parseInt(h.substr(i * 2, 2), 16); } return a; }
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var pk = reg.m.get(token);
      var sig = await subtle.sign({ name: 'ECDSA', hash: { name: hashName } }, pk, h2b(messageHex));
      return hx(sig);
    })()""",
)
private external fun jsEcdsaSign(
    token: Int,
    hashName: JsString,
    messageHex: JsString,
): Promise<JsString>

@JsFun(
    """(lengthBits) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      var subtle = globalThis.crypto.subtle;
      var k = await subtle.generateKey({ name: 'AES-GCM', length: lengthBits }, false, ['encrypt', 'decrypt']);
      var t = reg.n++; reg.m.set(t, k);
      return String(t);
    })()""",
)
private external fun jsGenerateAesGcm(lengthBits: Int): Promise<JsString>

@JsFun(
    """(token, ivHex, aadHex, ptHex) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function h2b(h) { var a = new Uint8Array(h.length / 2); for (var i = 0; i < a.length; i++) { a[i] = parseInt(h.substr(i * 2, 2), 16); } return a; }
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var k = reg.m.get(token);
      var params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
      if (aadHex.length > 0) params.additionalData = h2b(aadHex);
      var ct = await subtle.encrypt(params, k, h2b(ptHex));
      return hx(ct);
    })()""",
)
private external fun jsAesGcmSeal(
    token: Int,
    ivHex: JsString,
    aadHex: JsString,
    ptHex: JsString,
): Promise<JsString>

@JsFun(
    """(token, ivHex, aadHex, ctHex) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function h2b(h) { var a = new Uint8Array(h.length / 2); for (var i = 0; i < a.length; i++) { a[i] = parseInt(h.substr(i * 2, 2), 16); } return a; }
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var k = reg.m.get(token);
      var params = { name: 'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
      if (aadHex.length > 0) params.additionalData = h2b(aadHex);
      try {
        var pt = await subtle.decrypt(params, k, h2b(ctHex));
        return hx(pt);
      } catch (e) { return null; }
    })()""",
)
private external fun jsAesGcmOpen(
    token: Int,
    ivHex: JsString,
    aadHex: JsString,
    ctHex: JsString,
): Promise<JsString?>

@JsFun(
    """(curveName) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var alg = (curveName === 'X25519') ? { name: 'X25519' } : { name: 'ECDH', namedCurve: curveName };
      try {
        var kp = await subtle.generateKey(alg, false, ['deriveBits']);
        var rawPub = await subtle.exportKey('raw', kp.publicKey);
        var t = reg.n++; reg.m.set(t, kp.privateKey);
        return String(t) + ':' + hx(rawPub);
      } catch (e) {
        if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
        throw e;
      }
    })()""",
)
private external fun jsGenerateEcdh(curveName: JsString): Promise<JsString>

@JsFun(
    """(token, curveName, peerPublicHex, bits) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function h2b(h) { var a = new Uint8Array(h.length / 2); for (var i = 0; i < a.length; i++) { a[i] = parseInt(h.substr(i * 2, 2), 16); } return a; }
      function hx(b) { var a = new Uint8Array(b); var s = ''; for (var i = 0; i < a.length; i++) { s += a[i].toString(16).padStart(2, '0'); } return s; }
      var subtle = globalThis.crypto.subtle;
      var priv = reg.m.get(token);
      var isX = (curveName === 'X25519');
      var alg = isX ? { name: 'X25519' } : { name: 'ECDH', namedCurve: curveName };
      var pub = await subtle.importKey('raw', h2b(peerPublicHex), alg, false, []);
      var deriveAlg = isX ? { name: 'X25519', public: pub } : { name: 'ECDH', public: pub };
      var secret = await subtle.deriveBits(deriveAlg, priv, bits);
      return hx(secret);
    })()""",
)
private external fun jsEcdhDeriveBits(
    token: Int,
    curveName: JsString,
    peerPublicHex: JsString,
    bits: Int,
): Promise<JsString>

@JsFun(
    """(token) => { var g = globalThis; if (g.__bcNE) { g.__bcNE.m.delete(token); } }""",
)
private external fun jsReleaseHandle(token: Int)
