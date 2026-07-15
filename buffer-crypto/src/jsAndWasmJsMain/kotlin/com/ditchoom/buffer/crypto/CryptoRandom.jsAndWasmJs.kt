package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.WriteBuffer

/**
 * js/wasmJs CSPRNG backed by WebCrypto's `crypto.getRandomValues`. Unlike `SubtleCrypto`
 * (hashing/signing), `getRandomValues` is synchronous, so it satisfies the synchronous
 * [cryptoRandomInto] contract directly. Node 18+ exposes the same `globalThis.crypto`.
 */
actual fun cryptoRandomInto(dest: WriteBuffer) {
    val n = dest.remaining()
    var i = 0
    // Pulled one byte at a time so the same source compiles for both the JS and the Wasm
    // backend without typed-array marshalling. Crypto material is small (nonces/keys);
    // bulk random is not the intended use.
    while (i < n) {
        dest.writeByte(secureRandomByte().toByte())
        i++
    }
}

private fun secureRandomByte(): Int = js("(globalThis.crypto).getRandomValues(new Uint8Array(1))[0]")

// cryptoRandomInt() is implemented per-leaf (jsMain / wasmJsMain) rather than here: each reuses a
// module-level Int32Array scratch to avoid a per-call typed-array allocation, and a properly-typed
// reused array can't be expressed in the shared jsAndWasmJs source set (the JS and Wasm typed-array
// bindings differ).
