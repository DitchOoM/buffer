package com.ditchoom.buffer

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun <T> runTest(block: suspend () -> T): dynamic = GlobalScope.promise { block() }
