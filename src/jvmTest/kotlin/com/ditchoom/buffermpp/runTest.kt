@file:JvmName("RunTest")

package com.ditchoom.buffermpp

import kotlinx.coroutines.runBlocking

actual fun <T> runTest(block: suspend () -> T) {
    runBlocking { block() }
}