package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.zlib.Z_OK
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

// Shared setup/refusal steps for the zlib inflate drivers in AppleCompression.kt (one-shot) and
// AppleStreamingCompression.kt (streaming). Kept out of the inflate loops themselves: the loops are
// the zlib state machine and are best read as such, while these steps are straight-line
// initialisation and error mapping that would otherwise be copy-pasted at four call sites.

/**
 * Handles zlib's `Z_NEED_DICT` for the streaming inflate drivers: install [dictionary], or map the
 * two possible refusals — none supplied, or one zlib rejects (wrong Adler-32) — onto a typed
 * [CompressionException]. Shared by every `Z_NEED_DICT` branch so the mapping cannot drift.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applyDictionaryOrThrow(
    stream: CPointer<z_stream>,
    dictionary: ReadBuffer?,
) {
    val dict = dictionary ?: throw CompressionException("Dictionary required")
    val setResult = applyInflateDictionary(stream, dict)
    if (setResult != Z_OK) {
        throw CompressionException("inflateSetDictionary failed with code: $setResult")
    }
}

/**
 * [applyDictionaryOrThrow] for the one-shot inflate path, which owns [stream] outright: release
 * zlib's internal state with `inflateEnd` before throwing, since the caller's `finally` only frees
 * the `z_stream` struct itself.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applyDictionaryOrEndAndThrow(
    stream: CPointer<z_stream>,
    dictionary: ReadBuffer?,
) {
    if (dictionary == null) {
        inflateEnd(stream)
        throw CompressionException("Dictionary required")
    }
    val setResult = applyInflateDictionary(stream, dictionary)
    if (setResult != Z_OK) {
        inflateEnd(stream)
        throw CompressionException("inflateSetDictionary failed with code: $setResult")
    }
}

/**
 * Initialises [stream] for inflate: clear the allocator hooks, pick window bits for [algorithm],
 * and eagerly install [dictionary] for raw deflate. Raw streams carry no in-band `Z_NEED_DICT`
 * signal, so the dictionary must be set up front; zlib-wrapped streams signal reactively from the
 * inflate loop instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun initInflateStream(
    stream: z_stream,
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
) {
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null

    val result = inflateInit2(stream.ptr, resolveWindowBits(algorithm, WindowBits.Default))
    if (result != Z_OK) {
        throw CompressionException("inflateInit2 failed with code: $result")
    }

    if (algorithm == CompressionAlgorithm.Raw) {
        dictionary?.let { applyInflateDictionary(stream.ptr, it) }
    }
}
