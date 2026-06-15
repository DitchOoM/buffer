package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

actual fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors()

actual fun onebrcDefaultFactory(): BufferFactory = BufferFactory.deterministic()

/**
 * Parallel execution across CPU cores via coroutines [Dispatchers.Default] — identical to the native
 * actual. That dispatcher is a single long-lived pool sized to the core count, so it is reused across
 * every solve() call: no per-solve thread-pool creation/teardown (which dominated the small-file
 * benchmark when each solve spawned a fresh fixed pool). Each chunk gets its own [StationTable] on a
 * worker thread; the read-only mapping is shared and per-worker tables are merged by the caller.
 */
actual fun <T> runChunks(
    chunks: List<Chunk>,
    task: (Chunk) -> T,
): List<T> =
    if (chunks.size <= 1) {
        chunks.map { task(it) }
    } else {
        runBlocking {
            chunks
                .map { chunk -> async(Dispatchers.Default) { task(chunk) } }
                .awaitAll()
        }
    }
