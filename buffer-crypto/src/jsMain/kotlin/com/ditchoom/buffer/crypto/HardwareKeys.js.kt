package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * JS (browser / Node) WebCrypto glue for the non-exportable software key provider. Non-extractable
 * `CryptoKey`s live in a global registry (`globalThis.__bcNE`) keyed by an integer token; only the
 * token and hex-marshalled public/derived material cross the Kotlin/JS boundary. The `js(...)`
 * literals use `.then()` chains (Kotlin/JS rejects `async`/`await` inside `js("…")`) and reference
 * Kotlin params directly (no IIFE wrapper — a shadowing param breaks Kotlin's name substitution).
 */

internal actual val subtleGenerateKeyAvailable: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    jsSubtleGenerateKeyAvailable()
}

internal actual suspend fun webCryptoGenerateNonExportableEcdsa(curveName: String): String = jsGenerateEcdsa(curveName).await()

internal actual suspend fun webCryptoEcdsaSign(
    token: Int,
    hashName: String,
    messageHex: String,
): String = jsEcdsaSign(token, hashName, messageHex).await()

internal actual suspend fun webCryptoGenerateNonExportableAesGcm(lengthBits: Int): String = jsGenerateAesGcm(lengthBits).await()

internal actual suspend fun webCryptoAesGcmSealHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String = jsAesGcmSeal(token, ivHex, aadHex, plaintextHex).await()

internal actual suspend fun webCryptoAesGcmOpenHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String? = jsAesGcmOpen(token, ivHex, aadHex, ciphertextAndTagHex).await()

internal actual suspend fun webCryptoGenerateNonExportableEcdh(curveName: String): String = jsGenerateEcdh(curveName).await()

internal actual suspend fun webCryptoEcdhDeriveBits(
    token: Int,
    curveName: String,
    peerPublicHex: String,
    bits: Int,
): String = jsEcdhDeriveBits(token, curveName, peerPublicHex, bits).await()

internal actual fun webCryptoReleaseHandle(token: Int) {
    jsReleaseHandle(token)
}

// --- raw JS helpers ----------------------------------------------------------

private fun jsSubtleGenerateKeyAvailable(): Boolean =
    js(
        "(function(){ try { var s=(globalThis.crypto&&globalThis.crypto.subtle); " +
            "return !!(s&&typeof s.generateKey==='function'); } catch(e){ return false; } })()",
    ) as Boolean

// The registry accessor + hex helpers are inlined into each literal (a shared js() function can't be
// referenced by name from inside another js() template).
private const val REG =
    "var g=globalThis; if(!g.__bcNE){ g.__bcNE={m:new Map(),n:1}; } var reg=g.__bcNE;"
private const val HX =
    "function hx(b){ var a=new Uint8Array(b); var s=''; for(var i=0;i<a.length;i++){ s+=a[i].toString(16).padStart(2,'0'); } return s; }"
private const val H2B =
    "function h2b(h){ var a=new Uint8Array(h.length/2); for(var i=0;i<a.length;i++){ a[i]=parseInt(h.substr(i*2,2),16); } return a; }"

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsGenerateEcdsa(curveName: String): Promise<String> =
    js(
        """
        (function(){
          $REG $HX
          var subtle = globalThis.crypto.subtle;
          return subtle.generateKey({ name:'ECDSA', namedCurve: curveName }, false, ['sign']).then(function(kp){
            return subtle.exportKey('raw', kp.publicKey).then(function(rawPub){
              var t = reg.n++; reg.m.set(t, kp.privateKey);
              return String(t) + ':' + hx(rawPub);
            });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsEcdsaSign(
    token: Int,
    hashName: String,
    messageHex: String,
): Promise<String> =
    js(
        """
        (function(){
          $REG $HX $H2B
          var subtle = globalThis.crypto.subtle;
          var pk = reg.m.get(token);
          return subtle.sign({ name:'ECDSA', hash:{ name: hashName } }, pk, h2b(messageHex)).then(function(sig){ return hx(sig); });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsGenerateAesGcm(lengthBits: Int): Promise<String> =
    js(
        """
        (function(){
          $REG
          var subtle = globalThis.crypto.subtle;
          return subtle.generateKey({ name:'AES-GCM', length: lengthBits }, false, ['encrypt','decrypt']).then(function(k){
            var t = reg.n++; reg.m.set(t, k);
            return String(t);
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsAesGcmSeal(
    token: Int,
    ivHex: String,
    aadHex: String,
    ptHex: String,
): Promise<String> =
    js(
        """
        (function(){
          $REG $HX $H2B
          var subtle = globalThis.crypto.subtle;
          var k = reg.m.get(token);
          var params = { name:'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
          if (aadHex.length > 0) params.additionalData = h2b(aadHex);
          return subtle.encrypt(params, k, h2b(ptHex)).then(function(ct){ return hx(ct); });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsAesGcmOpen(
    token: Int,
    ivHex: String,
    aadHex: String,
    ctHex: String,
): Promise<String?> =
    js(
        """
        (function(){
          $REG $HX $H2B
          var subtle = globalThis.crypto.subtle;
          var k = reg.m.get(token);
          var params = { name:'AES-GCM', iv: h2b(ivHex), tagLength: 128 };
          if (aadHex.length > 0) params.additionalData = h2b(aadHex);
          return subtle.decrypt(params, k, h2b(ctHex)).then(function(pt){ return hx(pt); }).catch(function(e){ return null; });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsGenerateEcdh(curveName: String): Promise<String> =
    js(
        """
        (function(){
          $REG $HX
          var subtle = globalThis.crypto.subtle;
          var alg = (curveName === 'X25519') ? { name:'X25519' } : { name:'ECDH', namedCurve: curveName };
          return subtle.generateKey(alg, false, ['deriveBits']).then(function(kp){
            return subtle.exportKey('raw', kp.publicKey).then(function(rawPub){
              var t = reg.n++; reg.m.set(t, kp.privateKey);
              return String(t) + ':' + hx(rawPub);
            });
          }).catch(function(e){
            if (e && (e.name === 'NotSupportedError' || e.name === 'SyntaxError')) return 'UNSUPPORTED';
            throw e;
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsEcdhDeriveBits(
    token: Int,
    curveName: String,
    peerPublicHex: String,
    bits: Int,
): Promise<String> =
    js(
        """
        (function(){
          $REG $HX $H2B
          var subtle = globalThis.crypto.subtle;
          var priv = reg.m.get(token);
          var isX = (curveName === 'X25519');
          var alg = isX ? { name:'X25519' } : { name:'ECDH', namedCurve: curveName };
          return subtle.importKey('raw', h2b(peerPublicHex), alg, false, []).then(function(pub){
            var deriveAlg = isX ? { name:'X25519', public: pub } : { name:'ECDH', public: pub };
            return subtle.deriveBits(deriveAlg, priv, bits).then(function(secret){ return hx(secret); });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsReleaseHandle(token: Int) {
    js(
        """
        (function(){ var g=globalThis; if(g.__bcNE){ g.__bcNE.m.delete(token); } })()
        """,
    )
}
