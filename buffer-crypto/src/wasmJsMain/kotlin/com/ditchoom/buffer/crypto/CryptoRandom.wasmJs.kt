@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * A module-level `Int32Array(1)` reused across every [cryptoRandomInt] call so no typed array is
 * allocated per draw. Safe because the Wasm/JS backend is single-threaded — no concurrent access to
 * race on. (`js(...)` may only be a function body on Wasm, so the array is created via a helper.)
 */
private val randomScratch: JsAny = newInt32Scratch()

private fun newInt32Scratch(): JsAny = js("new Int32Array(1)")

/** One secure [Int] from `crypto.getRandomValues`, filling the reused [randomScratch]. */
internal actual fun cryptoRandomInt(): Int = fillRandomInt(randomScratch)

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun fillRandomInt(scratch: JsAny): Int = js("(globalThis.crypto.getRandomValues(scratch), scratch[0])")
