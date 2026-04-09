@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
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
    // Single copy from WASM linear memory to a new JS Uint8Array.
    // Safe for async operations — the copy won't be invalidated by memory.grow().
    val native = (this as? NativeMemoryAccess)
    if (native != null) {
        val offset = native.nativeAddress.toInt() + position()
        position(position() + remaining)
        return JsByteArray(jsCopyFromWasmMemory(offset, remaining))
    }
    val bytes = readByteArray(remaining)
    return bytes.toJsByteArray()
}

@JsFun(
    """
(offset, length) => {
    return new Uint8Array(wasmExports.memory.buffer, offset, length);
}
""",
)
private external fun jsViewWasmMemory(
    offset: Int,
    length: Int,
): JsAny

internal actual fun ReadBuffer.toJsByteArrayView(): JsByteArray {
    val remaining = remaining()
    if (remaining == 0) return emptyJsByteArray()
    // Zero-copy view on WASM linear memory. Only safe for synchronous consumption —
    // memory.grow() would invalidate this view. Sync zlib calls (gzipSync, etc.)
    // consume the input before returning, so no Kotlin allocation can intervene.
    val native = (this as? NativeMemoryAccess)
    if (native != null) {
        val offset = native.nativeAddress.toInt() + position()
        position(position() + remaining)
        return JsByteArray(jsViewWasmMemory(offset, remaining))
    }
    // Non-native buffer: must copy (no linear memory to view)
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

internal actual fun JsByteArray.toPlatformBuffer(bufferFactory: BufferFactory): ReadBuffer {
    val length = byteLength()
    if (length == 0) {
        return bufferFactory.allocate(0)
    }
    // Allocate via the provided factory (supports pool, direct, heap).
    // If the buffer has native memory access (LinearBuffer), copy JS data directly
    // into its WASM linear memory region. Single copy, no ByteArray intermediate.
    val buf = bufferFactory.allocate(length)
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
(algorithm, level, windowBits) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = { level: level };
    if (windowBits !== 0) options.windowBits = Math.abs(windowBits);
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
    windowBits: Int,
): JsAny

internal actual fun createCompressStream(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    windowBits: Int,
): NodeTransformHandle = NodeTransformHandle(jsCreateCompressStream(algorithm.toOrdinal(), level.value, windowBits))

@JsFun(
    """
(algorithm, windowBits) => {
    const m = 'zl' + 'ib';
    const zlib = require(m);
    const options = {};
    if (algorithm === 2) options.finishFlush = zlib.constants.Z_SYNC_FLUSH;
    if (windowBits !== 0) options.windowBits = Math.abs(windowBits);
    switch (algorithm) {
        case 0: return zlib.createInflate(options);
        case 1: return zlib.createGunzip(options);
        case 2: return zlib.createInflateRaw(options);
        default: throw new Error('Unknown algorithm: ' + algorithm);
    }
}
""",
)
private external fun jsCreateDecompressStream(
    algorithm: Int,
    windowBits: Int,
): JsAny

internal actual fun createDecompressStream(
    algorithm: CompressionAlgorithm,
    windowBits: Int,
): NodeTransformHandle = NodeTransformHandle(jsCreateDecompressStream(algorithm.toOrdinal(), windowBits))

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

@JsFun(
    """
(stream, chunk, flushFlag) => {
    const handle = stream._handle;
    if (!handle) throw new Error('zlib handle is null');
    const chunkSize = stream._chunkSize;
    const state = stream._writeState;
    const buffers = [];
    let nread = 0;
    let availInBefore = chunk.byteLength;
    let inOff = 0;
    let buffer = stream._outBuffer;
    let offset = stream._outOffset;
    let availOutBefore = chunkSize - offset;
    while (true) {
        handle.writeSync(flushFlag, chunk, inOff, availInBefore, buffer, offset, availOutBefore);
        const availOutAfter = state[0];
        const availInAfter = state[1];
        const have = availOutBefore - availOutAfter;
        if (have > 0) {
            buffers.push(buffer.slice(offset, offset + have));
            offset += have;
            nread += have;
        }
        if (availInAfter <= 0) break;
        if (availOutAfter === 0) {
            availOutBefore = chunkSize;
            offset = 0;
            buffer = Buffer.allocUnsafe(chunkSize);
        }
        inOff += (availInBefore - availInAfter);
        availInBefore = availInAfter;
    }
    stream._outBuffer = Buffer.allocUnsafe(chunkSize);
    stream._outOffset = 0;
    if (nread === 0) return new Uint8Array(0);
    const result = Buffer.concat(buffers, nread);
    return new Uint8Array(result.buffer, result.byteOffset, result.byteLength);
}
""",
)
private external fun jsProcessSyncPersistent(
    stream: JsAny,
    input: JsAny,
    flag: Int,
): JsAny

internal actual fun NodeTransformHandle.processSync(
    input: JsByteArray,
    flushFlag: Int,
): JsByteArray = JsByteArray(jsProcessSyncPersistent(ref, input.ref, flushFlag))

@JsFun(
    """
(stream, input, flag) => {
    return stream._processChunk(input, flag);
}
""",
)
private external fun jsProcessChunkOneShot(
    stream: JsAny,
    input: JsAny,
    flag: Int,
): JsAny

internal actual fun NodeTransformHandle.processSyncOneShot(
    input: JsByteArray,
    flushFlag: Int,
): JsByteArray = JsByteArray(jsProcessChunkOneShot(ref, input.ref, flushFlag))

@JsFun("() => { const m = 'zl' + 'ib'; return require(m).constants.Z_SYNC_FLUSH; }")
private external fun jsZSyncFlush(): Int

@JsFun("() => { const m = 'zl' + 'ib'; return require(m).constants.Z_FINISH; }")
private external fun jsZFinish(): Int

internal actual fun zlibSyncFlushFlag(): Int = jsZSyncFlush()

internal actual fun zlibFinishFlag(): Int = jsZFinish()

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
