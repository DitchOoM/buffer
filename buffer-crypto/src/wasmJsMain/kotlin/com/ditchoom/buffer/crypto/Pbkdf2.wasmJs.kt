@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

internal actual suspend fun webCryptoPbkdf2(
    hashName: String,
    passwordHex: String,
    saltHex: String,
    iterations: Int,
    bits: Int,
): String =
    jsPbkdf2(hashName.toJsString(), passwordHex.toJsString(), saltHex.toJsString(), iterations, bits)
        .await<JsString>()
        .toString()

@JsFun(
    """
    (hashName, passwordHex, saltHex, iterations, bits) => (async () => {
        const subtle = (globalThis.crypto).subtle;
        const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
        const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
        const key = await subtle.importKey('raw', h2b(passwordHex), { name: 'PBKDF2' }, false, ['deriveBits']);
        const derived = await subtle.deriveBits({ name: 'PBKDF2', hash: hashName, salt: h2b(saltHex), iterations: iterations }, key, bits);
        return b2h(derived);
    })()
    """,
)
private external fun jsPbkdf2(
    hashName: JsString,
    passwordHex: JsString,
    saltHex: JsString,
    iterations: Int,
    bits: Int,
): Promise<JsString>
