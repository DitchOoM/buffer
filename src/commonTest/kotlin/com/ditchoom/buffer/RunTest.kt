package com.ditchoom.buffer

expect fun <T> runTest(block: suspend () -> T)

fun <T> runTestBlocking(block: suspend () -> T) {
    runTest { block() }
}