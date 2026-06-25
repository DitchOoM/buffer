package com.ditchoom.buffer.crypto

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

/**
 * Awaits a [Promise] from a suspend function using only the stdlib coroutine intrinsics — the
 * crypto module deliberately does not depend on kotlinx-coroutines, so we bridge `then` ourselves.
 */
private suspend fun <T> Promise<T>.awaitResult(): T =
    suspendCoroutine { cont ->
        then(
            { value -> cont.resume(value) },
            { error -> cont.resumeWithException(toThrowable(error)) },
        )
    }

private fun toThrowable(error: dynamic): Throwable =
    if (error is Throwable) error else RuntimeException(error?.toString() ?: "promise rejected")

/**
 * JS WebCrypto bridge for signatures, via `dynamic` + Promise `await`. Reaches `globalThis.crypto`
 * (browser `window.crypto`, Node 16+ `globalThis.crypto`). All key/message/signature material
 * crosses as hex and is converted to/from `Uint8Array` in JS helpers.
 *
 * Ed25519 is feature-detected by attempting an import; engines without it reject, which we map to
 * "unavailable" so the capability contract holds.
 */

private val subtle: dynamic get() = js("(globalThis.crypto).subtle")

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun hexToU8(hex: String): dynamic =
    js(
        """
        (function(h){
            var a = new Uint8Array(h.length/2);
            for (var i=0;i<a.length;i++){ a[i]=parseInt(h.substr(i*2,2),16); }
            return a;
        })(hex)
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun u8ToHex(buf: dynamic): String =
    js(
        """
        (function(b){
            var a = new Uint8Array(b);
            var s='';
            for (var i=0;i<a.length;i++){ s += a[i].toString(16).padStart(2,'0'); }
            return s;
        })(buf)
        """,
    ) as String

private fun ecdsaCurve(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "P-256"
        SignatureScheme.EcdsaP384 -> "P-384"
        SignatureScheme.EcdsaP521 -> "P-521"
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

private fun ecdsaHash(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "SHA-256"
        SignatureScheme.EcdsaP384 -> "SHA-384"
        SignatureScheme.EcdsaP521 -> "SHA-512"
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

internal actual suspend fun webCryptoEd25519Available(): Boolean = ed25519Available()

private val ed25519AvailableCache: Promise<Boolean> by lazy { probeEd25519() }

private suspend fun ed25519Available(): Boolean =
    try {
        ed25519AvailableCache.awaitResult()
    } catch (_: Throwable) {
        false
    }

private fun probeEd25519(): Promise<Boolean> =
    Promise { resolve, _ ->
        try {
            val algo = js("{ name: 'Ed25519' }")
            val raw = hexToU8("0000000000000000000000000000000000000000000000000000000000000000")
            val usages = js("['verify']")
            subtle
                .importKey("raw", raw, algo, false, usages)
                .then({ _: dynamic -> resolve(true) }, { _: dynamic -> resolve(false) })
        } catch (_: Throwable) {
            resolve(false)
        }
    }

internal actual suspend fun webCryptoSign(
    scheme: SignatureScheme,
    privateMaterialHex: String,
    messageHex: String,
): String {
    val pkcs8 = hexToU8(privateMaterialHex)
    val msg = hexToU8(messageHex)
    val importAlgo: dynamic
    val signAlgo: dynamic
    if (scheme == SignatureScheme.Ed25519) {
        importAlgo = js("{ name: 'Ed25519' }")
        signAlgo = js("{ name: 'Ed25519' }")
    } else {
        val curve = ecdsaCurve(scheme)
        val hash = ecdsaHash(scheme)
        importAlgo = js("({ name: 'ECDSA', namedCurve: curve })")
        signAlgo = js("({ name: 'ECDSA', hash: { name: hash } })")
    }
    val key =
        subtle
            .importKey("pkcs8", pkcs8, importAlgo, false, js("['sign']"))
            .unsafeCast<Promise<dynamic>>()
            .awaitResult()
    val sig = subtle.sign(signAlgo, key, msg).unsafeCast<Promise<dynamic>>().awaitResult()
    return u8ToHex(sig)
}

private fun schemeTag(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.Ed25519 -> "Ed25519"
        SignatureScheme.EcdsaP256 -> "P256"
        SignatureScheme.EcdsaP384 -> "P384"
        SignatureScheme.EcdsaP521 -> "P521"
    }

// Generates a keypair and returns "<rawPrivHex>:<rawPubHex>". Ed25519: the 32-byte seed is the PKCS#8
// suffix after the fixed 16-byte RFC 8410 prefix; the public key exports as raw. ECDSA: the raw scalar
// is the JWK `d` (fixed-width base64url) and the public key the uncompressed SEC1 point (raw export).
@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsGenerateKeyPair(scheme: String): dynamic =
    js(
        """
        (function(sc){
            var subtle = (globalThis.crypto).subtle;
            var toHex = function(b){ var a=new Uint8Array(b); var s=''; for(var i=0;i<a.length;i++){ s+=a[i].toString(16).padStart(2,'0'); } return s; };
            var b64uToHex = function(s){ s=s.replace(/-/g,'+').replace(/_/g,'/'); while(s.length%4){ s+='='; } var bin=atob(s); var h=''; for(var i=0;i<bin.length;i++){ h+=bin.charCodeAt(i).toString(16).padStart(2,'0'); } return h; };
            if (sc === 'Ed25519') {
                return subtle.generateKey({ name:'Ed25519' }, true, ['sign','verify'])
                    .then(function(kp){ return Promise.all([subtle.exportKey('raw',kp.publicKey), subtle.exportKey('pkcs8',kp.privateKey)]); })
                    .then(function(a){ return toHex(a[1]).substring(32,96) + ':' + toHex(a[0]); });
            }
            var curve = sc === 'P256' ? 'P-256' : (sc === 'P384' ? 'P-384' : 'P-521');
            return subtle.generateKey({ name:'ECDSA', namedCurve:curve }, true, ['sign','verify'])
                .then(function(kp){ return Promise.all([subtle.exportKey('raw',kp.publicKey), subtle.exportKey('jwk',kp.privateKey)]); })
                .then(function(a){ return b64uToHex(a[1].d) + ':' + toHex(a[0]); });
        })(scheme)
        """,
    )

internal actual suspend fun webCryptoGenerateKeyPair(scheme: SignatureScheme): String =
    jsGenerateKeyPair(schemeTag(scheme)).unsafeCast<Promise<String>>().awaitResult()

internal actual suspend fun webCryptoVerify(
    scheme: SignatureScheme,
    publicMaterialHex: String,
    messageHex: String,
    signatureHex: String,
): Boolean =
    try {
        val pub = hexToU8(publicMaterialHex)
        val msg = hexToU8(messageHex)
        val sig = hexToU8(signatureHex)
        val importAlgo: dynamic
        val verifyAlgo: dynamic
        if (scheme == SignatureScheme.Ed25519) {
            importAlgo = js("{ name: 'Ed25519' }")
            verifyAlgo = js("{ name: 'Ed25519' }")
        } else {
            val curve = ecdsaCurve(scheme)
            val hash = ecdsaHash(scheme)
            importAlgo = js("({ name: 'ECDSA', namedCurve: curve })")
            verifyAlgo = js("({ name: 'ECDSA', hash: { name: hash } })")
        }
        val key =
            subtle
                .importKey("raw", pub, importAlgo, false, js("['verify']"))
                .unsafeCast<Promise<dynamic>>()
                .awaitResult()
        subtle.verify(verifyAlgo, key, sig, msg).unsafeCast<Promise<Boolean>>().awaitResult()
    } catch (_: Throwable) {
        // A rejected import (off-curve point, wrong length) or rejected verify ⇒ not accepted.
        false
    }
