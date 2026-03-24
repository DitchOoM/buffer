@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.compression

import com.ditchoom.buffer.NativeMemoryAccess
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

// ============================================================================
// Platform detection
// ============================================================================

@JsFun("() => typeof process !== 'undefined' && process.versions != null && process.versions.node != null")
private external fun jsIsNodeJs(): Boolean

internal actual val isNodeJs: Boolean by lazy { jsIsNodeJs() }

// ============================================================================
// JsByteArray — wraps a JS Uint8Array as opaque JsAny
// ============================================================================

internal actual class JsByteArray(
    val ref: JsAny,
)

@JsFun("(arr) => arr.length")
private external fun jsArrayLength(arr: JsAny): Int

internal actual fun JsByteArray.byteLength(): Int = jsArrayLength(ref)

@JsFun("() => new Uint8Array(0)")
private external fun jsEmptyUint8Array(): JsAny

internal actual fun emptyJsByteArray(): JsByteArray = JsByteArray(jsEmptyUint8Array())

// ============================================================================
// ReadBuffer → JsByteArray (copy from WASM linear memory to JS Uint8Array)
// ============================================================================

@JsFun(
    """
(offset, length) => {
    const memory = wasmExports.memory.buffer;
    const src = new Uint8Array(memory, offset, length);
    const copy = new Uint8Array(length);
    copy.set(src);
    return copy;
}
""",
)
private external fun jsCopyFromWasmMemory(
    offset: Int,
    length: Int,
): JsAny

internal actual fun ReadBuffer.toJsByteArray(): JsByteArray {
    val remaining = remaining()
    if (remaining == 0) return emptyJsByteArray()
    // Zero-copy path: if buffer has native memory (LinearBuffer),
    // copy directly from WASM linear memory offset to JS Uint8Array.
    val native = (this as? NativeMemoryAccess)
    if (native != null) {
        val offset = native.nativeAddress.toInt() + position()
        position(position() + remaining)
        return JsByteArray(jsCopyFromWasmMemory(offset, remaining))
    }
    // Fallback: copy through ByteArray for non-native buffers
    val bytes = readByteArray(remaining)
    return bytes.toJsByteArray()
}

@OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)
private fun ByteArray.toJsByteArray(): JsByteArray {
    if (isEmpty()) return emptyJsByteArray()
    // ByteArray in Kotlin/WASM is stored in linear memory.
    // Use withScopedMemoryAllocator to pin and copy.
    val size = this.size
    kotlin.wasm.unsafe.withScopedMemoryAllocator { allocator ->
        val ptr = allocator.allocate(size)
        for (i in 0 until size) {
            (ptr + i).storeByte(this[i])
        }
        return JsByteArray(jsCopyFromWasmMemory(ptr.address.toInt(), size))
    }
}

// ============================================================================
// JsByteArray combine
// ============================================================================

@JsFun(
    """
(arrays, totalSize) => {
    const combined = new Uint8Array(totalSize);
    let offset = 0;
    for (let i = 0; i < arrays.length; i++) {
        combined.set(arrays[i], offset);
        offset += arrays[i].length;
    }
    return combined;
}
""",
)
private external fun jsCombineArrays(
    arrays: JsAny,
    totalSize: Int,
): JsAny

@JsFun("() => []")
private external fun jsNewArray(): JsAny

@JsFun("(arr, item) => { arr.push(item); }")
private external fun jsArrayPush(
    arr: JsAny,
    item: JsAny,
)

internal actual fun combineJsByteArrays(
    arrays: List<JsByteArray>,
    totalSize: Int,
): JsByteArray {
    val jsArr = jsNewArray()
    for (a in arrays) jsArrayPush(jsArr, a.ref)
    return JsByteArray(jsCombineArrays(jsArr, totalSize))
}

// ============================================================================
// JsByteArray → PlatformBuffer (copy from JS Uint8Array to WASM linear memory)
// ============================================================================

@JsFun(
    """
(jsArray, dstOffset) => {
    const memory = wasmExports.memory.buffer;
    const dst = new Uint8Array(memory, dstOffset, jsArray.length);
    dst.set(jsArray);
}
""",
)
private external fun jsCopyToWasmMemory(
    jsArray: JsAny,
    dstOffset: Int,
)

internal actual fun JsByteArray.toPlatformBuffer(allocator: BufferAllocator): ReadBuffer {
    val length = byteLength()
    if (length == 0) {
        return allocator.allocate(0)
    }
    // Allocate via the provided allocator (supports pool, direct, heap).
    // If the buffer has native memory access (LinearBuffer), copy JS data directly
    // into its WASM linear memory region. Single copy, no ByteArray intermediate.
    val buf = allocator.allocate(length)
    val native = (buf as? NativeMemoryAccess)
    if (native != null) {
        jsCopyToWasmMemory(ref, native.nativeAddress.toInt())
        buf.position(length)
        buf.resetForRead()
    } else {
        // Fallback: should not happen on wasmJs, but be safe
        val bytes = ByteArray(length)
        @OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)
        kotlin.wasm.unsafe.withScopedMemoryAllocator { allocator ->
            val ptr = allocator.allocate(length)
            jsCopyToWasmMemory(ref, ptr.address.toInt())
            for (i in 0 until length) {
                bytes[i] = (ptr + i).loadByte()
            }
        }
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
    }
    return buf
}

// ============================================================================
// Node.js sync zlib
// ============================================================================

@JsFun(
    """
(input, algorithm, level) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = { level: level };
    switch (algorithm) {
        case 0: return zlib.deflateSync(input, options);
        case 1: return zlib.gzipSync(input, options);
        case 2: return zlib.deflateRawSync(input, options);
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsZlibSync(
    input: JsAny,
    algorithm: Int,
    level: Int,
): JsAny

internal actual fun nodeZlibSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray = JsByteArray(jsZlibSync(input.ref, algorithm.toOrdinal(), level.value))

@JsFun(
    """
(input, algorithm, level) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = { level: level, finishFlush: zlib.constants.Z_SYNC_FLUSH };
    switch (algorithm) {
        case 0: return zlib.deflateSync(input, options);
        case 1: return zlib.gzipSync(input, options);
        case 2: return zlib.deflateRawSync(input, options);
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsZlibSyncFlush(
    input: JsAny,
    algorithm: Int,
    level: Int,
): JsAny

internal actual fun nodeZlibSyncFlush(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray = JsByteArray(jsZlibSyncFlush(input.ref, algorithm.toOrdinal(), level.value))

@JsFun(
    """
(input, algorithm) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    switch (algorithm) {
        case 0: return zlib.inflateSync(input);
        case 1: return zlib.gunzipSync(input);
        case 2: {
            const options = { finishFlush: zlib.constants.Z_SYNC_FLUSH };
            return zlib.inflateRawSync(input, options);
        }
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsZlibDecompressSync(
    input: JsAny,
    algorithm: Int,
): JsAny

internal actual fun nodeZlibDecompressSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray = JsByteArray(jsZlibDecompressSync(input.ref, algorithm.toOrdinal()))

// ============================================================================
// Browser CompressionStream / DecompressionStream
// ============================================================================

@JsFun(
    """
async (data, format) => {
    const cs = new CompressionStream(format);
    const blob = new Blob([data]);
    const stream = blob.stream().pipeThrough(cs);
    const response = new Response(stream);
    const buffer = await response.arrayBuffer();
    return new Uint8Array(buffer);
}
""",
)
private external fun jsBrowserCompress(data: JsAny, format: JsString): JsAny // Promise

internal actual suspend fun browserCompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray {
    val result = jsBrowserCompress(input.ref, algorithm.toBrowserFormat().toJsString()).unsafeCast<Promise<JsAny?>>().await() as JsAny
    return JsByteArray(result)
}

@JsFun(
    """
async (data, format) => {
    const ds = new DecompressionStream(format);
    const blob = new Blob([data]);
    const stream = blob.stream().pipeThrough(ds);
    const response = new Response(stream);
    const buffer = await response.arrayBuffer();
    return new Uint8Array(buffer);
}
""",
)
private external fun jsBrowserDecompress(data: JsAny, format: JsString): JsAny // Promise

internal actual suspend fun browserDecompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray {
    val result = jsBrowserDecompress(input.ref, algorithm.toBrowserFormat().toJsString()).unsafeCast<Promise<JsAny?>>().await() as JsAny
    return JsByteArray(result)
}

// ============================================================================
// Node.js Transform stream
// ============================================================================

internal actual class NodeTransformHandle(
    val ref: JsAny,
)

@JsFun(
    """
(algorithm, level) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = { level: level };
    switch (algorithm) {
        case 0: return zlib.createDeflate(options);
        case 1: return zlib.createGzip(options);
        case 2: return zlib.createDeflateRaw(options);
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsCreateCompressStream(
    algorithm: Int,
    level: Int,
): JsAny

internal actual fun createCompressStream(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): NodeTransformHandle = NodeTransformHandle(jsCreateCompressStream(algorithm.toOrdinal(), level.value))

@JsFun(
    """
(algorithm) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = {};
    if (algorithm === 2) options.finishFlush = zlib.constants.Z_SYNC_FLUSH;
    switch (algorithm) {
        case 0: return zlib.createInflate(options);
        case 1: return zlib.createGunzip(options);
        case 2: return zlib.createInflateRaw(options);
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsCreateDecompressStream(algorithm: Int): JsAny

internal actual fun createDecompressStream(algorithm: CompressionAlgorithm): NodeTransformHandle =
    NodeTransformHandle(jsCreateDecompressStream(algorithm.toOrdinal()))

@JsFun(
    """
(stream, inputs) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('readable', () => {
            let chunk;
            while (null !== (chunk = stream.read())) {
                chunks.push(new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.length));
            }
        });
        stream.once('error', (err) => reject(new Error(String(err))));
        for (let i = 0; i < inputs.length; i++) {
            stream.write(inputs[i]);
        }
        stream.flush(zlib.constants.Z_SYNC_FLUSH, () => {
            let chunk;
            while (null !== (chunk = stream.read())) {
                chunks.push(new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.length));
            }
            stream.removeAllListeners('readable');
            resolve(chunks);
        });
    });
}
""",
)
private external fun jsWriteAndFlush(stream: JsAny, inputs: JsAny): JsAny // Promise

@JsFun("(arr) => arr.length")
private external fun jsJsArrayLength(arr: JsAny): Int

@JsFun("(arr, i) => arr[i]")
private external fun jsJsArrayGet(
    arr: JsAny,
    i: Int,
): JsAny

internal actual suspend fun NodeTransformHandle.writeAndFlush(inputs: List<JsByteArray>): List<JsByteArray> {
    val jsInputs = jsNewArray()
    for (input in inputs) jsArrayPush(jsInputs, input.ref)
    val resultArr = jsWriteAndFlush(ref, jsInputs).unsafeCast<Promise<JsAny?>>().await() as JsAny
    val len = jsJsArrayLength(resultArr)
    return (0 until len).map { JsByteArray(jsJsArrayGet(resultArr, it)) }
}

@JsFun(
    """
(stream, inputs) => {
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', (chunk) => chunks.push(chunk));
        stream.on('error', (err) => reject(new Error(String(err))));
        stream.on('end', () => {
            const buf = Buffer.concat(chunks);
            resolve(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
        });
        for (let i = 0; i < inputs.length; i++) {
            stream.write(inputs[i]);
        }
        stream.end();
    });
}
""",
)
private external fun jsWriteAndEnd(stream: JsAny, inputs: JsAny): JsAny // Promise

internal actual suspend fun NodeTransformHandle.writeAndEnd(inputs: List<JsByteArray>): JsByteArray {
    val jsInputs = jsNewArray()
    for (input in inputs) jsArrayPush(jsInputs, input.ref)
    val result = jsWriteAndEnd(ref, jsInputs).unsafeCast<Promise<JsAny?>>().await() as JsAny
    return JsByteArray(result)
}

@JsFun("(stream) => stream.destroy()")
private external fun jsDestroyStream(stream: JsAny)

internal actual fun NodeTransformHandle.destroy() = jsDestroyStream(ref)

// ============================================================================
// One-shot Transform stream
// ============================================================================

@JsFun(
    """
(inputs, algorithm, level) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = { level: level };
    let stream;
    switch (algorithm) {
        case 0: stream = zlib.createDeflate(options); break;
        case 1: stream = zlib.createGzip(options); break;
        case 2: stream = zlib.createDeflateRaw(options); break;
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', (chunk) => chunks.push(chunk));
        stream.on('error', (err) => reject(new Error(String(err))));
        stream.on('end', () => {
            const buf = Buffer.concat(chunks);
            resolve(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
        });
        for (let i = 0; i < inputs.length; i++) stream.write(inputs[i]);
        stream.end();
    });
}
""",
)
private external fun jsTransformCompressOneShot(inputs: JsAny, algorithm: Int, level: Int): JsAny // Promise

internal actual suspend fun nodeTransformCompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray {
    val jsInputs = jsNewArray()
    for (input in inputs) jsArrayPush(jsInputs, input.ref)
    val result = jsTransformCompressOneShot(jsInputs, algorithm.toOrdinal(), level.value).unsafeCast<Promise<JsAny?>>().await() as JsAny
    return JsByteArray(result)
}

@JsFun(
    """
(inputs, algorithm) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = {};
    if (algorithm === 2) options.finishFlush = zlib.constants.Z_SYNC_FLUSH;
    let stream;
    switch (algorithm) {
        case 0: stream = zlib.createInflate(options); break;
        case 1: stream = zlib.createGunzip(options); break;
        case 2: stream = zlib.createInflateRaw(options); break;
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', (chunk) => chunks.push(chunk));
        stream.on('error', (err) => reject(new Error(String(err))));
        stream.on('end', () => {
            const buf = Buffer.concat(chunks);
            resolve(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
        });
        for (let i = 0; i < inputs.length; i++) stream.write(inputs[i]);
        stream.end();
    });
}
""",
)
private external fun jsTransformDecompressOneShot(inputs: JsAny, algorithm: Int): JsAny // Promise

internal actual suspend fun nodeTransformDecompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
): JsByteArray {
    val jsInputs = jsNewArray()
    for (input in inputs) jsArrayPush(jsInputs, input.ref)
    val result = jsTransformDecompressOneShot(jsInputs, algorithm.toOrdinal()).unsafeCast<Promise<JsAny?>>().await() as JsAny
    return JsByteArray(result)
}

// ============================================================================
// Utility
// ============================================================================

/** Map CompressionAlgorithm to ordinal for passing to JS (0=Deflate, 1=Gzip, 2=Raw). */
private fun CompressionAlgorithm.toOrdinal(): Int =
    when (this) {
        CompressionAlgorithm.Deflate -> 0
        CompressionAlgorithm.Gzip -> 1
        CompressionAlgorithm.Raw -> 2
    }
