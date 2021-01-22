package com.ditchoom.bytebuffer

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun <T> runTest(block: suspend () -> T): dynamic = code(block)

fun <T> code(block: suspend () -> T) {
    GlobalScope.promise { block() }
}