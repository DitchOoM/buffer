@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.BufferConstants.BYTE_1_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_2_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_3_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_MASK
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_3
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

@JsFun(
    """
(offset, length, value) => {
    const memory = wasmExports.memory.buffer;
    const arr = new Uint8Array(memory, offset, length);
    arr.fill(value);
}
""",
)
private external fun memset(
    offset: Int,
    length: Int,
    value: Int,
)

@JsFun(
    """
(srcOffset, dstOffset, length) => {
    const memory = wasmExports.memory.buffer;
    const src = new Uint8Array(memory, srcOffset, length);
    const dst = new Uint8Array(memory, dstOffset, length);
    dst.set(src);
}
""",
)
private external fun memcpyJs(
    srcOffset: Int,
    dstOffset: Int,
    length: Int,
)

actual object UnsafeMemory {
    actual val isSupported: Boolean = true

    private fun ptr(address: Long): Pointer = Pointer(address.toUInt())

    actual fun getByte(address: Long): Byte = ptr(address).loadByte()

    actual fun putByte(
        address: Long,
        value: Byte,
    ) {
        ptr(address).storeByte(value)
    }

    actual fun getShort(address: Long): Short = ptr(address).loadShort()

    actual fun putShort(
        address: Long,
        value: Short,
    ) {
        ptr(address).storeShort(value)
    }

    actual fun getInt(address: Long): Int = ptr(address).loadInt()

    actual fun putInt(
        address: Long,
        value: Int,
    ) {
        ptr(address).storeInt(value)
    }

    actual fun getLong(address: Long): Long = ptr(address).loadLong()

    actual fun putLong(
        address: Long,
        value: Long,
    ) {
        ptr(address).storeLong(value)
    }

    actual fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    ) {
        memcpyJs(srcAddress.toInt(), dstAddress.toInt(), size.toInt())
    }

    actual fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    ) {
        memset(address.toInt(), size.toInt(), value.toInt())
    }

    actual fun copyMemoryToArray(
        srcAddress: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        // Copy 4 bytes at a time using Int loads (pure WASM i32 instructions, no JS boundary)
        var addr = srcAddress
        var i = destOffset
        val end = destOffset + length
        while (i + Int.SIZE_BYTES <= end) {
            val v = ptr(addr).loadInt()
            dest[i] = v.toByte()
            dest[i + 1] = (v shr BYTE_1_SHIFT).toByte()
            dest[i + 2] = (v shr BYTE_2_SHIFT).toByte()
            dest[i + WORD_BYTE_3] = (v shr BYTE_3_SHIFT).toByte()
            addr += Int.SIZE_BYTES
            i += Int.SIZE_BYTES
        }
        // Handle remaining bytes
        while (i < end) {
            dest[i] = ptr(addr).loadByte()
            addr++
            i++
        }
    }

    actual fun copyMemoryFromArray(
        src: ByteArray,
        srcOffset: Int,
        dstAddress: Long,
        length: Int,
    ) {
        // Copy 4 bytes at a time using Int stores (pure WASM i32 instructions, no JS boundary)
        var addr = dstAddress
        var i = srcOffset
        val end = srcOffset + length
        while (i + Int.SIZE_BYTES <= end) {
            val v =
                (src[i].toInt() and BYTE_MASK) or
                    ((src[i + 1].toInt() and BYTE_MASK) shl BYTE_1_SHIFT) or
                    ((src[i + 2].toInt() and BYTE_MASK) shl BYTE_2_SHIFT) or
                    ((src[i + WORD_BYTE_3].toInt() and BYTE_MASK) shl BYTE_3_SHIFT)
            ptr(addr).storeInt(v)
            addr += Int.SIZE_BYTES
            i += Int.SIZE_BYTES
        }
        // Handle remaining bytes
        while (i < end) {
            ptr(addr).storeByte(src[i])
            addr++
            i++
        }
    }
}
