package com.ditchoom.buffer

expect fun <T> runTest(block: suspend () -> T)
