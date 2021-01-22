@file:JvmName("RunTest")

package com.ditchoom.bytebuffer

import kotlinx.coroutines.runBlocking

actual fun <T> runTest(block: suspend () -> T) {
    runBlocking { block() }
}