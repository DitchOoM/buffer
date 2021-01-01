package com.ditchoom.buffermpp

expect fun <T> runTest(block: suspend () -> T)

fun <T> runTestBlocking(block: suspend () -> T) {
    runTest { block() }
}