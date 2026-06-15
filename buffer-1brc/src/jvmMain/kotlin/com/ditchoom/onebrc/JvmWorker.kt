package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import java.util.concurrent.Callable
import java.util.concurrent.Executors

actual fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors()

actual fun onebrcDefaultFactory(): BufferFactory = BufferFactory.deterministic()

actual fun <T> runChunks(
    chunks: List<Chunk>,
    task: (Chunk) -> T,
): List<T> {
    if (chunks.isEmpty()) return emptyList()
    if (chunks.size == 1) return listOf(task(chunks[0]))

    val pool = Executors.newFixedThreadPool(minOf(chunks.size, defaultParallelism()))
    try {
        val futures = chunks.map { chunk -> pool.submit(Callable { task(chunk) }) }
        return futures.map { it.get() }
    } finally {
        pool.shutdown()
    }
}
