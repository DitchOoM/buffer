package com.ditchoom.buffer

/**
 * Platform-specific native data type for read-only access.
 *
 * This wrapper provides safe access to platform-native memory:
 * - **JVM/Android**: `java.nio.ByteBuffer` (direct, read-only) - access via `.byteBuffer`
 * - **Apple**: `NSData` - access via `.nsData`
 * - **JS**: `ArrayBuffer` - access via `.arrayBuffer`
 * - **Linux**: `NativeBuffer` - access via `.nativeBuffer`
 * - **WASM**: `LinearBuffer` - access via `.linearBuffer`
 *
 * In common code, you can pass `NativeData` to platform-specific APIs.
 * Use platform-specific accessors to get the underlying native type.
 */
expect class NativeData

/**
 * Platform-specific native data type for mutable access.
 *
 * This wrapper provides safe access to platform-native memory:
 * - **JVM/Android**: `java.nio.ByteBuffer` (direct, mutable) - access via `.byteBuffer`
 * - **Apple**: `NSMutableData` - access via `.nsMutableData`
 * - **JS**: `Int8Array` - access via `.int8Array`
 * - **Linux**: `NativeBuffer` - access via `.nativeBuffer`
 * - **WASM**: `LinearBuffer` - access via `.linearBuffer`
 *
 * In common code, you can pass `MutableNativeData` to platform-specific APIs.
 * Use platform-specific accessors to get the underlying native type.
 */
expect class MutableNativeData

/**
 * Converts the remaining bytes of this buffer to native platform data.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * Returns the platform-specific native memory representation wrapped in [NativeData]:
 * - **JVM/Android**: Direct read-only `ByteBuffer`
 * - **Apple**: `NSData` (zero-copy view via subdataWithRange when possible)
 * - **JS**: `ArrayBuffer` (zero-copy if full buffer, copies for partial views)
 * - **Linux**: `NativeBuffer` with native memory
 * - **WASM**: `LinearBuffer` with linear memory
 *
 * **Zero-copy behavior**: Each platform optimizes for zero-copy when the buffer
 * is already in native format. Copies only when conversion is necessary.
 *
 * @return Platform-specific native data for the remaining bytes
 */
expect fun ReadBuffer.toNativeData(): NativeData

/**
 * Converts the remaining bytes of this buffer to mutable native platform data.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * Returns the platform-specific mutable native memory representation wrapped in [MutableNativeData]:
 * - **JVM/Android**: Direct mutable `ByteBuffer`
 * - **Apple**: `NSMutableData`
 * - **JS**: `Int8Array` (always zero-copy via subarray)
 * - **Linux**: `NativeBuffer` with native memory
 * - **WASM**: `LinearBuffer` with linear memory
 *
 * **Zero-copy behavior**: Each platform optimizes for zero-copy when the buffer
 * is already in native format. Copies only when conversion is necessary.
 *
 * @return Platform-specific mutable native data for the remaining bytes
 */
expect fun PlatformBuffer.toMutableNativeData(): MutableNativeData

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy behavior**:
 * - If the buffer is backed by a ByteArray (ManagedMemoryAccess) and the full array matches
 *   the remaining content, returns the backing array directly (zero-copy)
 * - Otherwise, copies the remaining bytes to a new ByteArray
 *
 * @return ByteArray containing the buffer's remaining content
 */
expect fun ReadBuffer.toByteArray(): ByteArray
