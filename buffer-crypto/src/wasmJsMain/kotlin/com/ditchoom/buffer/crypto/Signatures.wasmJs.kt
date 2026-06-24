@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * Awaits a [Promise] from a suspend function using only stdlib coroutine intrinsics — the crypto
 * module deliberately does not depend on kotlinx-coroutines, so we bridge `then` ourselves.
 */
private suspend fun <T : JsAny?> Promise<T>.awaitResult(): T =
    suspendCoroutine { cont ->
        then(
            onFulfilled = { value ->
                cont.resume(value)
                null
            },
            onRejected = { error ->
                cont.resumeWithException(JsException(error))
                null
            },
        )
    }

private class JsException(
    val ref: JsAny?,
) : RuntimeException("promise rejected")

/**
 * wasmJs WebCrypto bridge for signatures, via `@JsFun` externals. The hex<->Uint8Array conversion
 * and the entire WebCrypto promise chain live in JS (returning hex / boolean), so nothing but
 * primitive strings and booleans crosses the WASM boundary — no typed-array marshalling, and the
 * `dynamic`-free WASM backend stays happy.
 *
 * Ed25519 is feature-detected by attempting an import; engines without it reject ⇒ unavailable.
 */

@JsFun(
    """
() => {
    try {
        return (globalThis.crypto).subtle.importKey(
            'raw',
            new Uint8Array(32),
            { name: 'Ed25519' },
            false,
            ['verify']
        ).then(() => true, () => false);
    } catch (e) { return Promise.resolve(false); }
}
""",
)
private external fun jsProbeEd25519(): Promise<JsBoolean>

@JsFun(
    """
(scheme, privHex, msgHex) => {
    const subtle = (globalThis.crypto).subtle;
    const toU8 = (h) => { const a = new Uint8Array(h.length/2); for (let i=0;i<a.length;i++) a[i]=parseInt(h.substr(i*2,2),16); return a; };
    const toHex = (b) => { const a = new Uint8Array(b); let s=''; for (let i=0;i<a.length;i++) s += a[i].toString(16).padStart(2,'0'); return s; };
    let importAlgo, signAlgo;
    if (scheme === 'Ed25519') { importAlgo = { name: 'Ed25519' }; signAlgo = { name: 'Ed25519' }; }
    else {
        const curve = scheme === 'P256' ? 'P-256' : (scheme === 'P384' ? 'P-384' : 'P-521');
        const hash = scheme === 'P256' ? 'SHA-256' : (scheme === 'P384' ? 'SHA-384' : 'SHA-512');
        importAlgo = { name: 'ECDSA', namedCurve: curve };
        signAlgo = { name: 'ECDSA', hash: { name: hash } };
    }
    return subtle.importKey('pkcs8', toU8(privHex), importAlgo, false, ['sign'])
        .then((k) => subtle.sign(signAlgo, k, toU8(msgHex)))
        .then((sig) => toHex(sig));
}
""",
)
private external fun jsSign(
    scheme: String,
    privHex: String,
    msgHex: String,
): Promise<JsString>

@JsFun(
    """
(scheme, pubHex, msgHex, sigHex) => {
    const subtle = (globalThis.crypto).subtle;
    const toU8 = (h) => { const a = new Uint8Array(h.length/2); for (let i=0;i<a.length;i++) a[i]=parseInt(h.substr(i*2,2),16); return a; };
    let importAlgo, verifyAlgo;
    if (scheme === 'Ed25519') { importAlgo = { name: 'Ed25519' }; verifyAlgo = { name: 'Ed25519' }; }
    else {
        const curve = scheme === 'P256' ? 'P-256' : (scheme === 'P384' ? 'P-384' : 'P-521');
        const hash = scheme === 'P256' ? 'SHA-256' : (scheme === 'P384' ? 'SHA-384' : 'SHA-512');
        importAlgo = { name: 'ECDSA', namedCurve: curve };
        verifyAlgo = { name: 'ECDSA', hash: { name: hash } };
    }
    return subtle.importKey('raw', toU8(pubHex), importAlgo, false, ['verify'])
        .then((k) => subtle.verify(verifyAlgo, k, toU8(sigHex), toU8(msgHex)))
        .then((ok) => !!ok, () => false);
}
""",
)
private external fun jsVerify(
    scheme: String,
    pubHex: String,
    msgHex: String,
    sigHex: String,
): Promise<JsBoolean>

@JsFun(
    """
(scheme) => {
    const subtle = (globalThis.crypto).subtle;
    const toHex = (b) => { const a = new Uint8Array(b); let s=''; for (let i=0;i<a.length;i++) s += a[i].toString(16).padStart(2,'0'); return s; };
    const b64uToHex = (s) => { s = s.replace(/-/g,'+').replace(/_/g,'/'); while (s.length % 4) s += '='; const bin = atob(s); let h=''; for (let i=0;i<bin.length;i++) h += bin.charCodeAt(i).toString(16).padStart(2,'0'); return h; };
    if (scheme === 'Ed25519') {
        return subtle.generateKey({ name: 'Ed25519' }, true, ['sign','verify'])
            .then((kp) => Promise.all([subtle.exportKey('raw', kp.publicKey), subtle.exportKey('pkcs8', kp.privateKey)]))
            .then((arr) => toHex(arr[1]).substring(32, 96) + ':' + toHex(arr[0]));
    }
    const curve = scheme === 'P256' ? 'P-256' : (scheme === 'P384' ? 'P-384' : 'P-521');
    return subtle.generateKey({ name: 'ECDSA', namedCurve: curve }, true, ['sign','verify'])
        .then((kp) => Promise.all([subtle.exportKey('raw', kp.publicKey), subtle.exportKey('jwk', kp.privateKey)]))
        .then((arr) => b64uToHex(arr[1].d) + ':' + toHex(arr[0]));
}
""",
)
private external fun jsGenerateKeyPair(scheme: String): Promise<JsString>

private fun schemeTag(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.Ed25519 -> "Ed25519"
        SignatureScheme.EcdsaP256 -> "P256"
        SignatureScheme.EcdsaP384 -> "P384"
        SignatureScheme.EcdsaP521 -> "P521"
    }

internal actual suspend fun webCryptoEd25519Available(): Boolean =
    try {
        jsProbeEd25519().awaitResult().toBoolean()
    } catch (_: Throwable) {
        false
    }

internal actual suspend fun webCryptoSign(
    scheme: SignatureScheme,
    privateMaterialHex: String,
    messageHex: String,
): String = jsSign(schemeTag(scheme), privateMaterialHex, messageHex).awaitResult().toString()

internal actual suspend fun webCryptoGenerateKeyPair(scheme: SignatureScheme): String =
    jsGenerateKeyPair(schemeTag(scheme)).awaitResult().toString()

internal actual suspend fun webCryptoVerify(
    scheme: SignatureScheme,
    publicMaterialHex: String,
    messageHex: String,
    signatureHex: String,
): Boolean =
    try {
        jsVerify(schemeTag(scheme), publicMaterialHex, messageHex, signatureHex).awaitResult().toBoolean()
    } catch (_: Throwable) {
        false
    }
