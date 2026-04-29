package com.ditchoom.buffer.compression

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

internal actual val isNodeJs: Boolean by lazy {
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

internal actual fun JsByteArray.toPlatformBuffer(allocator: BufferAllocator): ReadBuffer {
    val length = array.length
    if (length == 0) return allocator.allocate(0)
    // For pool allocators, copy into the pool buffer. For default/heap, wrap directly.
    return if (allocator is BufferAllocator.FromPool) {
        val buf = allocator.allocate(length)
        val src = JsBuffer(array)
        buf.write(src)
        buf.resetForRead()
        buf
    } else {
        JsBuffer(array)
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
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
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
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
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
): JsByteArray {
    val zlib = getNodeZlib()
    val inputArray = input.array
    val result: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gunzipSync(inputArray).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.inflateSync(inputArray).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> {
                val options = js("{}")
                options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
                zlib.inflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
            }
        }
    return result.toJsByteArray()
}

// ============================================================================
// Browser CompressionStream / DecompressionStream
// ============================================================================

private fun createCompressionStream(format: String): dynamic = js("new CompressionStream(format)")

private fun createDecompressionStream(format: String): dynamic = js("new DecompressionStream(format)")

private fun createBlob(data: Uint8Array): dynamic = js("new Blob([data])")

private fun getStream(blob: dynamic): dynamic = blob.stream()

private fun pipeThrough(
    stream: dynamic,
    transform: dynamic,
): dynamic = stream.pipeThrough(transform)

private fun createResponse(stream: dynamic): dynamic = js("new Response(stream)")

private fun getArrayBuffer(response: dynamic): Promise<dynamic> = response.arrayBuffer().unsafeCast<Promise<dynamic>>()

private fun createUint8ArrayFromBuffer(buffer: dynamic): Uint8Array = js("new Uint8Array(buffer)").unsafeCast<Uint8Array>()

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
): NodeTransformHandle {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    val stream: dynamic =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.createGzip(options)
            CompressionAlgorithm.Deflate -> zlib.createDeflate(options)
            CompressionAlgorithm.Raw -> zlib.createDeflateRaw(options)
        }
    return NodeTransformHandle(stream)
}

internal actual fun createDecompressStream(algorithm: CompressionAlgorithm): NodeTransformHandle {
    val zlib = getNodeZlib()
    val options = js("{}")
    if (algorithm == CompressionAlgorithm.Raw) {
        options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
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

// ============================================================================
// One-shot Transform stream helpers
// ============================================================================

internal actual suspend fun nodeTransformCompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
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
): JsByteArray {
    val zlib = getNodeZlib()
    val options: dynamic = js("{}")
    val algStr = algorithm.toNodeString()
    if (algStr == "raw") {
        options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
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
