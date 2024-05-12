package com.ditchoom.buffer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

@OptIn(DelicateCoroutinesApi::class)
actual fun <T> runTest(block: suspend () -> T): dynamic = GlobalScope.promise { block() }
