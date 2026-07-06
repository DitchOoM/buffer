package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise

// ============================================================================
// Platform detection
// ============================================================================

internal actual val isNodeJs: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
}

// ============================================================================
// JsByteArray — wraps Int8Array on JS
// ============================================================================

internal actual class JsByteArray(
    val array: Int8Array,
)

internal actual fun JsByteArray.byteLength(): Int = array.length

internal actual fun ReadBuffer.toJsByteArray(): JsByteArray = toJsByteArrayImpl()

internal actual fun ReadBuffer.toJsByteArrayView(): JsByteArray = toJsByteArrayImpl()

// JS is always zero-copy via subarray — no distinction needed between view and copy.
private fun ReadBuffer.toJsByteArrayImpl(): JsByteArray {
    val remaining = remaining()
    return if (this is JsBuffer) {
        val sub = buffer.subarray(position(), position() + remaining)
        position(position() + remaining)
        JsByteArray(sub)
    } else {
        val bytes = readByteArray(remaining)
        JsByteArray(bytes.unsafeCast<Int8Array>())
    }
}

internal actual fun combineJsByteArrays(
    arrays: List<JsByteArray>,
    totalSize: Int,
): JsByteArray {
    val combined = Int8Array(totalSize)
    var offset = 0
    for (a in arrays) {
        combined.set(a.array, offset)
        offset += a.array.length
    }
    return JsByteArray(combined)
}

internal actual fun emptyJsByteArray(): JsByteArray = JsByteArray(Int8Array(0))

internal actual fun JsByteArray.toPlatformBuffer(bufferFactory: BufferFactory): ReadBuffer {
    val length = array.length
    if (length == 0) return bufferFactory.allocate(0)
    // For non-default factories (e.g. pool-backed), copy into the factory's buffer.
    // For the default factory, wrap the underlying Int8Array directly for zero-copy.
    return if (bufferFactory === BufferFactory.Default) {
        JsBuffer(array)
    } else {
        val buf = bufferFactory.allocate(length)
        val src = JsBuffer(array)
        buf.write(src)
        buf.resetForRead()
        buf
    }
}

// ============================================================================
// Helpers: typed array conversions
// ============================================================================

private fun JsByteArray.toUint8Array(): Uint8Array = Uint8Array(array.buffer, array.byteOffset, array.length)

private fun Uint8Array.toJsByteArray(): JsByteArray = JsByteArray(Int8Array(buffer, byteOffset, length))

// ============================================================================
// Node.js zlib module loading
// ============================================================================

private fun getNodeZlib(): dynamic {
    val moduleName = "zl" + "ib"

    @Suppress("UNUSED_VARIABLE")
    val m = moduleName
    return js("require(m)")
}

// ============================================================================
// Node.js sync zlib
// ============================================================================

internal actual fun nodeZlibSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    windowBits: WindowBits,
    dictionary: JsByteArray?,
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    if (windowBits != WindowBits.Default) {
        // Node's zlib options take windowBits as the log2 size (9..15). The function
        // name (gzipSync / deflateSync / deflateRawSync) selects the format; Node
        // applies the raw negation / gzip +16 internally.
        options["windowBits"] = windowBits.sizeLog2
    }
    if (dictionary != null) {
        options["dictionary"] = dictionary.array
    }
    val inputArray = input.array
    val result: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gzipSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.deflateSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> zlib.deflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
        }
    return result.toJsByteArray()
}

internal actual fun nodeZlibSyncFlush(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    windowBits: WindowBits,
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
    if (windowBits != WindowBits.Default) {
        options["windowBits"] = windowBits.sizeLog2
    }
    val inputArray = input.array
    val result: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gzipSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.deflateSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> zlib.deflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
        }
    return result.toJsByteArray()
}

internal actual fun nodeZlibDecompressSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    dictionary: JsByteArray?,
): JsByteArray {
    val zlib = getNodeZlib()
    val inputArray = input.array
    val result: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gunzipSync(inputArray).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> {
                val options = js("{}")
                if (dictionary != null) options["dictionary"] = dictionary.array
                zlib.inflateSync(inputArray, options).unsafeCast<Uint8Array>()
            }
            CompressionAlgorithm.Raw -> {
                val options = js("{}")
                options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
                if (dictionary != null) options["dictionary"] = dictionary.array
                zlib.inflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
            }
        }
    return result.toJsByteArray()
}

// ============================================================================
// Browser CompressionStream / DecompressionStream
// ============================================================================

// Parameters below are referenced inside the js(...) template strings, which detekt
// cannot see, so they are flagged unused despite being required by the generated JS.
@Suppress("UnusedParameter")
private fun createCompressionStream(format: String): dynamic = js("new CompressionStream(format)")

@Suppress("UnusedParameter")
private fun createDecompressionStream(format: String): dynamic = js("new DecompressionStream(format)")

@Suppress("UnusedParameter")
private fun createBlob(data: Uint8Array): dynamic = js("new Blob([data])")

private fun getStream(blob: dynamic): dynamic = blob.stream()

private fun pipeThrough(
    stream: dynamic,
    transform: dynamic,
): dynamic = stream.pipeThrough(transform)

@Suppress("UnusedParameter")
private fun createResponse(stream: dynamic): dynamic = js("new Response(stream)")

private fun getArrayBuffer(response: dynamic): Promise<dynamic> = response.arrayBuffer().unsafeCast<Promise<dynamic>>()

@Suppress("UnusedParameter")
private fun createUint8ArrayFromBuffer(buffer: dynamic) = js("new Uint8Array(buffer)").unsafeCast<Uint8Array>()

internal actual suspend fun browserCompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray {
    val format = algorithm.toBrowserFormat()
    val data = input.toUint8Array()
    val cs = createCompressionStream(format)
    val blob = createBlob(data)
    val inputStream = getStream(blob)
    val compressedStream = pipeThrough(inputStream, cs)
    val response = createResponse(compressedStream)
    val arrayBuffer = getArrayBuffer(response).await()
    return createUint8ArrayFromBuffer(arrayBuffer).toJsByteArray()
}

internal actual suspend fun browserDecompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray {
    val format = algorithm.toBrowserFormat()
    val data = input.toUint8Array()
    val ds = createDecompressionStream(format)
    val blob = createBlob(data)
    val inputStream = getStream(blob)
    val decompressedStream = pipeThrough(inputStream, ds)
    val response = createResponse(decompressedStream)
    val arrayBuffer = getArrayBuffer(response).await()
    return createUint8ArrayFromBuffer(arrayBuffer).toJsByteArray()
}

// ============================================================================
// Node.js Transform stream
// ============================================================================

internal actual class NodeTransformHandle(
    val stream: dynamic,
)

internal actual fun createCompressStream(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    windowBits: WindowBits,
    dictionary: JsByteArray?,
): NodeTransformHandle {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    if (windowBits != WindowBits.Default) {
        options["windowBits"] = windowBits.sizeLog2
    }
    if (dictionary != null) {
        options["dictionary"] = dictionary.array
    }
    val stream: dynamic =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.createGzip(options)
            CompressionAlgorithm.Deflate -> zlib.createDeflate(options)
            CompressionAlgorithm.Raw -> zlib.createDeflateRaw(options)
        }
    return NodeTransformHandle(stream)
}

internal actual fun createDecompressStream(
    algorithm: CompressionAlgorithm,
    windowBits: WindowBits,
    dictionary: JsByteArray?,
): NodeTransformHandle {
    val zlib = getNodeZlib()
    val options = js("{}")
    if (algorithm == CompressionAlgorithm.Raw) {
        options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
    }
    if (windowBits != WindowBits.Default) {
        options["windowBits"] = windowBits.sizeLog2
    }
    if (dictionary != null) {
        options["dictionary"] = dictionary.array
    }
    val stream: dynamic =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.createGunzip(options)
            CompressionAlgorithm.Deflate -> zlib.createInflate(options)
            CompressionAlgorithm.Raw -> zlib.createInflateRaw(options)
        }
    return NodeTransformHandle(stream)
}

internal actual suspend fun NodeTransformHandle.writeAndFlush(inputs: List<JsByteArray>): List<JsByteArray> {
    val zlib = getNodeZlib()
    val currentStream = stream
    val chunks = mutableListOf<JsByteArray>()

    Promise<Unit> { resolve, reject ->
        currentStream.once("error") { err: dynamic -> reject(Error(err.toString())) }
        currentStream.on("readable") {
            while (true) {
                val chunk = currentStream.read()
                if (chunk == null) break
                chunks.add(chunk.unsafeCast<Uint8Array>().toJsByteArray())
            }
        }
        for (input in inputs) {
            currentStream.write(input.toUint8Array())
        }
        currentStream.flush(zlib.constants.Z_SYNC_FLUSH) {
            resolve(Unit)
        }
    }.await()

    // Read any remaining data
    while (true) {
        val chunk = currentStream.read()
        if (chunk == null) break
        chunks.add(chunk.unsafeCast<Uint8Array>().toJsByteArray())
    }
    currentStream.removeAllListeners("readable")
    return chunks
}

internal actual suspend fun NodeTransformHandle.writeAndEnd(inputs: List<JsByteArray>): JsByteArray {
    val result =
        Promise<Uint8Array> { resolve, reject ->
            val outputChunks = js("[]")
            stream.on("data") { chunk: dynamic -> outputChunks.push(chunk) }
            stream.on("error") { err: dynamic -> reject(Error(err.toString())) }
            stream.on("end") {
                val concatenated: dynamic = js("Buffer").concat(outputChunks)
                val uint8 = Uint8Array(concatenated.buffer, concatenated.byteOffset, concatenated.length)
                resolve(uint8)
            }
            for (input in inputs) {
                stream.write(input.toUint8Array())
            }
            stream.end()
        }.await()
    return result.toJsByteArray()
}

internal actual fun NodeTransformHandle.destroy() {
    stream.destroy()
}

internal actual fun NodeTransformHandle.resetState() {
    stream.reset()
}

internal actual fun NodeTransformHandle.processSync(
    input: JsByteArray,
    flushFlag: Int,
): JsByteArray {
    val result = processSyncPersistent(stream, input.toUint8Array(), flushFlag)
    return result.toJsByteArray()
}

/**
 * Synchronously processes [chunk] through a persistent zlib stream using the C++ handle's
 * `writeSync()` method directly. Replicates Node's internal `processChunkSync` but does NOT
 * close the handle afterwards, preserving the LZ77 sliding window for context takeover.
 *
 * Exit logic mirrors Node's `processChunkSync`: break when `availOutAfter > 0` (output buffer
 * has room left ⇒ all pending output has been drained, including the Z_SYNC_FLUSH trailer
 * `00 00 FF FF`). Breaking earlier on `availInAfter <= 0` is incorrect when the output buffer
 * fills exactly at input exhaustion — the trailer bytes are still pending and would be lost,
 * producing truncated frames the peer can't decode (caught by Autobahn 12.2.8+).
 *
 * Z_FINISH/Z_STREAM_END handling stays in [processSyncOneShot], which delegates to Node's own
 * `_processChunk` so the v24+ `closed_` assertion is handled internally.
 */
private fun processSyncPersistent(
    stream: dynamic,
    chunk: Uint8Array,
    flushFlag: Int,
): Uint8Array {
    val handle = stream._handle ?: throw IllegalStateException("zlib handle is null — stream was closed or finished")
    val chunkSize: Int = stream._chunkSize as Int
    val state = stream._writeState
    val buffers = js("[]")
    var nread = 0
    var availInBefore = chunk.length
    var inOff = 0
    var buffer = stream._outBuffer
    var offset: Int = stream._outOffset as Int
    var availOutBefore = chunkSize - offset

    while (true) {
        handle.writeSync(flushFlag, chunk, inOff, availInBefore, buffer, offset, availOutBefore)
        val availOutAfter: Int = state[0] as Int
        val availInAfter: Int = state[1] as Int
        val inDelta = availInBefore - availInAfter
        val have = availOutBefore - availOutAfter
        if (have > 0) {
            buffers.push(buffer.slice(offset, offset + have))
            offset += have
            nread += have
        }
        if (availOutAfter == 0 || offset >= chunkSize) {
            availOutBefore = chunkSize
            offset = 0
            buffer = js("Buffer").allocUnsafe(chunkSize)
        }
        if (availOutAfter == 0) {
            inOff += inDelta
            availInBefore = availInAfter
            continue
        }
        break
    }
    stream._outBuffer = buffer
    stream._outOffset = offset

    if (nread == 0) return Uint8Array(0)
    val result = js("Buffer").concat(buffers, nread)
    return Uint8Array(result.buffer, result.byteOffset, result.length)
}

internal actual fun NodeTransformHandle.processSyncOneShot(
    input: JsByteArray,
    flushFlag: Int,
): JsByteArray {
    // `_processChunk` without a callback is synchronous. Handles the writeSync loop
    // internally and calls `_close()` at the end, destroying the C++ handle.
    val result = stream._processChunk(input.toUint8Array(), flushFlag)
    return (result as Uint8Array).toJsByteArray()
}

internal actual fun zlibSyncFlushFlag(): Int = getNodeZlib().constants.Z_SYNC_FLUSH as Int

internal actual fun zlibFinishFlag(): Int = getNodeZlib().constants.Z_FINISH as Int

// ============================================================================
// One-shot Transform stream helpers
// ============================================================================

internal actual suspend fun nodeTransformCompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    dictionary: JsByteArray?,
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    if (dictionary != null) {
        options["dictionary"] = dictionary.array
    }
    val algStr = algorithm.toNodeString()

    val result =
        Promise<Uint8Array> { resolve, reject ->
            val stream: dynamic =
                when (algStr) {
                    "gzip" -> zlib.createGzip(options)
                    "deflate" -> zlib.createDeflate(options)
                    else -> zlib.createDeflateRaw(options)
                }
            val chunks = js("[]")
            stream.on("data") { chunk: dynamic -> chunks.push(chunk) }
            stream.on("error") { err: dynamic -> reject(Error(err.toString())) }
            stream.on("end") {
                val r: dynamic = js("Buffer").concat(chunks)
                resolve(Uint8Array(r.buffer, r.byteOffset, r.length))
            }
            for (input in inputs) {
                stream.write(input.toUint8Array())
            }
            stream.end()
        }.await()
    return result.toJsByteArray()
}

internal actual suspend fun nodeTransformDecompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
    dictionary: JsByteArray?,
): JsByteArray {
    val zlib = getNodeZlib()
    val options: dynamic = js("{}")
    val algStr = algorithm.toNodeString()
    if (algStr == "raw") {
        options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
    }
    if (dictionary != null) {
        options["dictionary"] = dictionary.array
    }

    val result =
        Promise<Uint8Array> { resolve, reject ->
            val stream: dynamic =
                when (algStr) {
                    "gzip" -> zlib.createGunzip(options)
                    "deflate" -> zlib.createInflate(options)
                    else -> zlib.createInflateRaw(options)
                }
            val chunks = js("[]")
            stream.on("data") { chunk: dynamic -> chunks.push(chunk) }
            stream.on("error") { err: dynamic -> reject(Error(err.toString())) }
            stream.on("end") {
                val r: dynamic = js("Buffer").concat(chunks)
                resolve(Uint8Array(r.buffer, r.byteOffset, r.length))
            }
            for (input in inputs) {
                stream.write(input.toUint8Array())
            }
            stream.end()
        }.await()
    return result.toJsByteArray()
}
