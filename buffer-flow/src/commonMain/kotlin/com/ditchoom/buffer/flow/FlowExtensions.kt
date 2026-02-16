package com.ditchoom.buffer.flow

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Transforms a [Flow] of arbitrarily chunked strings into a [Flow] of complete lines.
 * Handles both `\n` and `\r\n` line endings. Emits trailing content without a final
 * newline as a last element.
 */
fun Flow<String>.lines(): Flow<String> =
    flow {
        var remainder = ""
        collect { chunk ->
            val combined = remainder + chunk
            var start = 0
            while (true) {
                val idx = combined.indexOf('\n', start)
                if (idx == -1) break
                val lineEnd = if (idx > start && combined[idx - 1] == '\r') idx - 1 else idx
                emit(combined.substring(start, lineEnd))
                start = idx + 1
            }
            remainder = if (start > 0) combined.substring(start) else combined
        }
        if (remainder.isNotEmpty()) {
            emit(if (remainder.endsWith('\r')) remainder.dropLast(1) else remainder)
        }
    }

/**
 * Transforms each [ReadBuffer] in the flow by applying the given [transform] function.
 *
 * Composes with compression:
 * ```
 * socket.readFlow().mapBuffer { decompress(it, Gzip).getOrThrow() }
 * ```
 */
fun Flow<ReadBuffer>.mapBuffer(transform: (ReadBuffer) -> ReadBuffer): Flow<ReadBuffer> = map { buf -> transform(buf) }

/**
 * Converts a [Flow] of [ReadBuffer]s into a [Flow] of [String]s.
 * Each buffer is read from its current position to its limit.
 */
fun Flow<ReadBuffer>.asStringFlow(charset: Charset = Charset.UTF8): Flow<String> = map { buf -> buf.readString(buf.remaining(), charset) }
