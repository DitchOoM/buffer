package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

internal actual suspend fun webCryptoPbkdf2(
    hashName: String,
    passwordHex: String,
    saltHex: String,
    iterations: Int,
    bits: Int,
): String = jsPbkdf2(hashName, passwordHex, saltHex, iterations, bits).await()

// Promise chains (not async/await): the Kotlin/JS `js(...)` intrinsic rejects async statements.
@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsPbkdf2(
    hashName: String,
    passwordHex: String,
    saltHex: String,
    iterations: Int,
    bits: Int,
): Promise<String> =
    js(
        """
        (function () {
            const subtle = (globalThis.crypto).subtle;
            const h2b = (h) => { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i*2,2),16); return a; };
            const b2h = (buf) => { const a = new Uint8Array(buf); let s = ''; for (let i = 0; i < a.length; i++) s += a[i].toString(16).padStart(2,'0'); return s; };
            return subtle.importKey('raw', h2b(passwordHex), { name: 'PBKDF2' }, false, ['deriveBits'])
                .then((key) => subtle.deriveBits({ name: 'PBKDF2', hash: hashName, salt: h2b(saltHex), iterations: iterations }, key, bits))
                .then((derived) => b2h(derived));
        })()
        """,
    )
