package com.ditchoom.buffer.crypto

/**
 * A module-level `Int32Array(1)` reused across every [cryptoRandomInt] call so no typed array is
 * allocated per draw. Safe because JS is single-threaded — there is no concurrent access to race on.
 */
private val randomScratch: dynamic = newInt32Scratch()

private fun newInt32Scratch(): dynamic = js("new Int32Array(1)")

/** One secure [Int] from `crypto.getRandomValues`, filling the reused [randomScratch]. */
internal actual fun cryptoRandomInt(): Int = fillRandomInt(randomScratch)

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun fillRandomInt(scratch: dynamic): Int = js("(globalThis.crypto.getRandomValues(scratch), scratch[0])")
