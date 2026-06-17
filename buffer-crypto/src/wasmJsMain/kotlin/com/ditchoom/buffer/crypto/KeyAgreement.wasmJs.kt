@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * wasmJs WebCrypto key-agreement glue. Mirrors the JS actual but marshals through `JsString`
 * (wasm boundary) and `@JsFun` externals; the same `crypto.subtle` calls run underneath.
 */

internal actual val webCryptoSupportsX25519: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    jsX25519Supported()
}

internal actual suspend fun webCryptoGenerateKeyPair(curveName: String): WebCryptoKeyPair {
    // Marshalled as "publicHex|privateHex" — single Promise, no shared global state.
    val combined = jsGenerateKeyPair(curveName).await().toString()
    val sep = combined.indexOf('|')
    require(sep > 0) { "malformed key-pair marshalling" }
    return WebCryptoKeyPair(
        publicHex = combined.substring(0, sep),
        privateHex = combined.substring(sep + 1),
    )
}

internal actual suspend fun webCryptoDeriveSharedSecret(
    curveName: String,
    privateHex: String,
    peerPublicHex: String,
): String = jsDeriveSharedSecret(curveName, privateHex, peerPublicHex).await().toString()

// --- externals ---------------------------------------------------------------

@JsFun(
    """() => {
      try {
        var s = (globalThis.crypto && globalThis.crypto.subtle);
        return !!(s && typeof s.generateKey === 'function');
      } catch (e) { return false; }
    }""",
)
private external fun jsX25519Supported(): Boolean

@JsFun(
    """(curveName) => (async () => {
      var subtle = globalThis.crypto.subtle;
      var alg = (curveName === 'X25519') ? { name: 'X25519' } : { name: 'ECDH', namedCurve: curveName };
      function hex(u8) { var s = ''; for (var i = 0; i < u8.length; i++) { var h = u8[i].toString(16); if (h.length < 2) h = '0' + h; s += h; } return s; }
      try {
        var kp = await subtle.generateKey(alg, true, ['deriveBits']);
        var rawPub = new Uint8Array(await subtle.exportKey('raw', kp.publicKey));
        var pkcs8 = new Uint8Array(await subtle.exportKey('pkcs8', kp.privateKey));
        return hex(rawPub) + '|' + hex(pkcs8);
      } catch (e) {
        // A missing algorithm (e.g. X25519 on an older engine) surfaces as NotSupportedError →
        // map to the sentinel so the Kotlin layer throws UnsupportedOperationException, not a leak.
        if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
        throw e;
      }
    })()""",
)
private external fun jsGenerateKeyPair(curveName: String): Promise<JsString>

@JsFun(
    """(curveName, privateHex, peerPublicHex) => (async () => {
      var subtle = globalThis.crypto.subtle;
      var isX = (curveName === 'X25519');
      var alg = isX ? { name: 'X25519' } : { name: 'ECDH', namedCurve: curveName };
      function fromHex(h) { var a = new Uint8Array(h.length / 2); for (var i = 0; i < a.length; i++) { a[i] = parseInt(h.substr(i * 2, 2), 16); } return a; }
      function hex(u8) { var s = ''; for (var i = 0; i < u8.length; i++) { var x = u8[i].toString(16); if (x.length < 2) x = '0' + x; s += x; } return s; }
      var bits;
      if (isX || curveName === 'P-256') bits = 256;
      else if (curveName === 'P-384') bits = 384;
      else bits = 528;
      try {
        var priv = await subtle.importKey('pkcs8', fromHex(privateHex), alg, false, ['deriveBits']);
        var pub = await subtle.importKey('raw', fromHex(peerPublicHex), alg, false, []);
        var deriveAlg = isX ? { name: 'X25519', public: pub } : { name: 'ECDH', public: pub };
        var secret = new Uint8Array(await subtle.deriveBits(deriveAlg, priv, bits));
        return hex(secret);
      } catch (e) {
        // Distinguish an absent algorithm (capability gap) from a bad peer key: the former is the
        // sentinel → UnsupportedOperationException; everything else falls through to InvalidPublicKey.
        if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
        throw e;
      }
    })()""",
)
private external fun jsDeriveSharedSecret(
    curveName: String,
    privateHex: String,
    peerPublicHex: String,
): Promise<JsString>
