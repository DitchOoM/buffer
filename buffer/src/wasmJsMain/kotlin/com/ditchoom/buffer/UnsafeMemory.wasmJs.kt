@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

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
private external fun memset(offset: Int, length: Int, value: Int)

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
private external fun memcpyJs(srcOffset: Int, dstOffset: Int, length: Int)

actual object UnsafeMemory {
    actual val isSupported: Boolean = true

    private fun ptr(address: Long): Pointer = Pointer(address.toUInt())

    actual fun getByte(address: Long): Byte = ptr(address).loadByte()

    actual fun putByte(address: Long, value: Byte) {
        ptr(address).storeByte(value)
    }

    actual fun getShort(address: Long): Short = ptr(address).loadShort()

    actual fun putShort(address: Long, value: Short) {
        ptr(address).storeShort(value)
    }

    actual fun getInt(address: Long): Int = ptr(address).loadInt()

    actual fun putInt(address: Long, value: Int) {
        ptr(address).storeInt(value)
    }

    actual fun getLong(address: Long): Long = ptr(address).loadLong()

    actual fun putLong(address: Long, value: Long) {
        ptr(address).storeLong(value)
    }

    actual fun copyMemory(srcAddress: Long, dstAddress: Long, size: Long) {
        memcpyJs(srcAddress.toInt(), dstAddress.toInt(), size.toInt())
    }

    actual fun setMemory(address: Long, size: Long, value: Byte) {
        memset(address.toInt(), size.toInt(), value.toInt())
    }
}
