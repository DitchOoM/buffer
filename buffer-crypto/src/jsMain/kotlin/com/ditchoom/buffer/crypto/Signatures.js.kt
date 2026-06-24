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
