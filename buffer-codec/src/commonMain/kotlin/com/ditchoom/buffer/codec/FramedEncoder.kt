package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.PoolReleasable

/**
 * Runtime entry point for the `@FramedBy` slicing-scheme encode emit. The
 * KSP-generated `@FramedBy` codec calls [encode], which:
 *
 *   1. Allocates a [GrowableWriteBuffer] with a slack region at the front sized
 *      to fit the worst-case header bytes plus the worst-case framing prefix.
 *   2. Positions the growable past the slack region and invokes [writeBody]
 *      to write the body fields into the buffer.
 *   3. Computes `bodyBytes = position - maxSlack` and asks [framingCodec] for
 *      the actual prefix width via `encodedWidth(bodyBytes)`.
 *   4. Right-flushes the prefix (and optional header) into the slack region
 *      so the wire bytes lie contiguously: `[header?][prefix][body]`.
 *   5. Returns a [ReadBuffer] slice spanning exactly those wire bytes.
 *
 * Body bytes are never moved — the slicing scheme leaves them at offset
 * `maxSlack` and writes the prefix backwards into the slack instead. This is
 * what makes the emit zero-memcpy for the body content (the design's headline
 * property).
 *
 * For a class with `@FramedBy(codec, after = "")`, [headerWireWidth] is `0`
 * and [writeHeader] is `null`. For `after = "X"`, [headerWireWidth] is the
 * Exact wire width of `X` and [writeHeader] writes that field into the
 * supplied buffer (the framework owns the header write so the gap between
 * header and prefix is zero — eliminating the 1-byte memmove that an
 * "in-variant header write" would otherwise require).
 */
public object FramedEncoder {
    public fun encode(
        factory: BufferFactory,
        framingCodec: BoundingLengthCodec<UInt>,
        context: EncodeContext,
        headerWireWidth: Int = 0,
        writeHeader: ((PlatformBuffer) -> Unit)? = null,
        initialBodyEstimate: Int = 256,
        writeBody: (WriteBuffer) -> Unit,
    ): ReadBuffer {
        val maxSlack = headerWireWidth + framingCodec.maxWireSize
        val growable = GrowableWriteBufferPool.acquire()
        try {
            growable.attach(factory, initialSize = maxSlack + initialBodyEstimate)
            growable.position(maxSlack)
            writeBody(growable)
            val bodyBytes = growable.position() - maxSlack
            // A codec that cannot tell the width from the length alone sizes it through wireBytes.
            val prefixBytes =
                framingCodec.encodedWidth(bodyBytes.toLong()).fold(
                    exact = { it },
                    backPatch = {
                        framingCodec.wireBytes(bodyBytes.toUInt(), context).fold(
                            exact = { it },
                            backPatch = { throw FramingSizeException(framingCodec) },
                        )
                    },
                )
            if (prefixBytes > framingCodec.maxWireSize) throw FramingSizeException(framingCodec)
            val actualPrefixWidth = prefixBytes.toInt()
            val sliceStart = maxSlack - actualPrefixWidth - headerWireWidth
            val buffer: PlatformBuffer = growable.innerBuffer()
            buffer.position(sliceStart)
            writeHeader?.invoke(buffer)
            framingCodec.encode(buffer, bodyBytes.toUInt(), context)
            buffer.position(sliceStart)
            buffer.setLimit(maxSlack + bodyBytes)
            val result = buffer.slice()
            return when {
                // Pooled: slice() bumped refcount; transfer the chunk's
                // initial reference to the returned slice so consumer's
                // `freeNativeMemory()` recycles the buffer.
                result is PoolReleasable -> {
                    buffer.freeNativeMemory()
                    result
                }
                // Deterministic / WASM-LinearBuffer / Native: chunk has an
                // explicit lifecycle but its slice's `freeNativeMemory()` is
                // a no-op (by design — slices share parent memory). Without
                // intervention the chunk's native memory leaks, since the
                // GrowableWriteBuffer wrapper goes out of scope here without
                // ever calling chunk.freeNativeMemory(). Wrap the slice in
                // an owner-transferring view so consumer's
                // `freeNativeMemory()` on the slice releases the chunk.
                buffer is CloseableBuffer && result !is OwnerTransferringSlice ->
                    OwnerTransferringSlice(result, buffer)
                else -> result
            }
        } finally {
            growable.detach()
            GrowableWriteBufferPool.release(growable)
        }
    }
}

/**
 * A `@FramedBy` header or length prefix must report an exact size that fits the framing
 * slack; [encoder] did not.
 */
public class FramingSizeException(
    public val encoder: Encoder<*>,
) : EncodeException(
        fieldPath = "@FramedBy",
        reason = "${encoder::class.simpleName} does not report an exact size within the framing slack",
    )
