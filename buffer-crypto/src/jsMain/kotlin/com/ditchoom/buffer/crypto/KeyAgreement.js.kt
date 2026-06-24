package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * JS (browser / Node) WebCrypto key-agreement glue. WebCrypto is Promise-based; the JS literals
 * here use `.then()` chains (Kotlin/JS rejects `async`/`await` inside `js("…")`). Marshalling is via
 * hex strings so the Kotlin side never holds a typed array. Kotlin variables are referenced directly
 * inside the literal (no IIFE wrapper — an inner shadowing param breaks Kotlin's name substitution).
 */

internal actual val webCryptoSupportsX25519: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    jsSubtleAvailable()
}

internal actual suspend fun webCryptoGenerateKeyPair(curveName: String): WebCryptoKeyPair {
    val combined = jsGenerateKeyPair(curveName).await()
    if (combined == UNSUPPORTED_SENTINEL) return WebCryptoKeyPair(UNSUPPORTED_SENTINEL, "")
    val sep = combined.indexOf('|')
    require(sep > 0) { "malformed key-pair marshalling" }
    return WebCryptoKeyPair(publicHex = combined.substring(0, sep), privateHex = combined.substring(sep + 1))
}

internal actual suspend fun webCryptoDeriveSharedSecret(
    curveName: String,
    privateHex: String,
    peerPublicHex: String,
): String = jsDeriveSharedSecret(curveName, privateHex, peerPublicHex).await()

// --- raw JS helpers ----------------------------------------------------------

private fun jsSubtleAvailable(): Boolean =
    js(
        "(function(){ try { var s=(globalThis.crypto&&globalThis.crypto.subtle); " +
            "return !!(s&&typeof s.generateKey==='function'); } catch(e){ return false; } })()",
    ) as Boolean

private fun jsGenerateKeyPair(curveName: String): Promise<String> {
    val subtle = js("globalThis.crypto.subtle")
    val alg = if (curveName == "X25519") js("{ name: 'X25519' }") else jsEcdhAlg(curveName)
    return js(
        """
        subtle.generateKey(alg, true, ['deriveBits']).then(function(kp){
          return Promise.all([ subtle.exportKey('raw', kp.publicKey), subtle.exportKey('pkcs8', kp.privateKey) ]).then(function(arr){
            function hx(u8){ var s=''; for(var i=0;i<u8.length;i++){ var h=u8[i].toString(16); if(h.length<2)h='0'+h; s+=h; } return s; }
            return hx(new Uint8Array(arr[0])) + '|' + hx(new Uint8Array(arr[1]));
          });
        }).catch(function(e){
          if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
          throw e;
        })
        """,
    )
}

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsDeriveSharedSecret(
    curveName: String,
    privateHex: String,
    peerPublicHex: String,
): Promise<String> {
    val subtle = js("globalThis.crypto.subtle")
    val isX = curveName == "X25519"
    val alg = if (isX) js("{ name: 'X25519' }") else jsEcdhAlg(curveName)
    val bits =
        if (isX || curveName == "P-256") {
            256
        } else if (curveName == "P-384") {
            384
        } else {
            528
        }
    return js(
        """
        (function(){
          function fromHex(h){ var a=new Uint8Array(h.length/2); for(var i=0;i<a.length;i++){ a[i]=parseInt(h.substr(i*2,2),16); } return a; }
          function hx(u8){ var s=''; for(var i=0;i<u8.length;i++){ var x=u8[i].toString(16); if(x.length<2)x='0'+x; s+=x; } return s; }
          return subtle.importKey('pkcs8', fromHex(privateHex), alg, false, ['deriveBits']).then(function(priv){
            return subtle.importKey('raw', fromHex(peerPublicHex), alg, false, []).then(function(pub){
              var deriveAlg = isX ? { name:'X25519', public:pub } : { name:'ECDH', public:pub };
              return subtle.deriveBits(deriveAlg, priv, bits).then(function(secret){ return hx(new Uint8Array(secret)); });
            });
          }).catch(function(e){
            if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
            throw e;
          });
        })()
        """,
    )
}

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsEcdhAlg(curveName: String): dynamic = js("({ name: 'ECDH', namedCurve: curveName })")
