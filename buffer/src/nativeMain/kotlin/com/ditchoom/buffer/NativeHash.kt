@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.buf_fnv1a_64
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

// FNV-1a-64 content digest computed entirely in C (raw pointer arithmetic, no per-element CPointer
// materialization). Shared by every native NativeMemoryAccess buffer — linux NativeBuffer and apple
// MutableDataBuffer — so the cinterop wiring lives in ONE place. The simd cinterop is registered for
// all native targets and cinterop commonization is on, so the shared nativeMain source set resolves
// buf_fnv1a_64 directly; keeping the call here means the optimization can't drift onto one native
// backend while silently missing another (which is how apple was first left out).
//
// Bit-identical to the common-code fnv1aHashRange fallback: same offset basis / prime, whole 8-byte
// words then the byte tail. [bigEndian] selects the per-word byteswap so the result matches the
// buffer's getLong() byteOrder (the host is little-endian; swap when the buffer is big-endian).
//
// @param dataStart pointer to the first byte to digest (caller has already applied the range offset)
// @param length    number of bytes to digest; the caller must have range-checked [dataStart, +length)
internal fun nativeFnv1aHashRange(
    dataStart: CPointer<ByteVar>,
    length: Int,
    bigEndian: Boolean,
): Long = buf_fnv1a_64(dataStart.reinterpret<UByteVar>(), length.convert(), if (bigEndian) 1 else 0)
