package com.ditchoom.buffer

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun <T> runTest(block: suspend () -> T): dynamic = code(block)

fun <T> code(block: suspend () -> T) {
    @Suppress("OPT_IN_USAGE")
    GlobalScope.promise { block() }
}