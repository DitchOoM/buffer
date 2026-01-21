---
sidebar_position: 3
title: Apple (iOS/macOS)
---

# Apple Platforms

Buffer on iOS, macOS, watchOS, and tvOS wraps Foundation's `NSData`.

## Implementation

| Zone | Apple Type |
|------|------------|
| `Heap` | `ByteArray` |
| `Direct` | `NSMutableData` |
| `SharedMemory` | `NSMutableData` |

## NSData Integration

Buffers wrap `NSMutableData` for seamless Foundation interop:

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeString("Hello from Kotlin!")
buffer.resetForRead()

// Access underlying NSData (platform-specific)
// val nsData: NSData = buffer.asNSData()
```

## Swift Interop

When exposing to Swift code:

```swift
// In Swift
let buffer = PlatformBuffer.companion.allocate(size: 1024)
buffer.writeInt(value: 42)
buffer.resetForRead()
let value = buffer.readInt()
```

## Memory Management

Memory is managed automatically through Kotlin/Native GC and Objective-C ARC cooperation:

- **Kotlin wrapper** (`MutableDataBuffer`/`ByteArrayBuffer`) - managed by Kotlin/Native GC
- **Underlying `NSData`/`NSMutableData`** - managed by Objective-C ARC

When the Kotlin buffer object is garbage collected, the reference to the underlying NSData is released, allowing ARC to deallocate it.

```kotlin
fun processData(): PlatformBuffer {
    val buffer = PlatformBuffer.allocate(1024)
    buffer.writeBytes(data)
    buffer.resetForRead()
    return buffer  // Kotlin GC + ARC manage lifecycle automatically
}

// No manual cleanup needed - memory is released when buffer is unreachable
```

:::note Implementation Detail
The `close()` method on Apple buffers is a no-op since ARC handles cleanup automatically. The `SuspendCloseable` interface is implemented for API consistency across platforms.
:::

## Native Data Conversion

Convert buffers to Apple-native `NSData`/`NSMutableData` for Foundation API interop:

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeBytes(data)
buffer.resetForRead()

// Get read-only NSData (zero-copy for MutableDataBuffer)
val nativeData = buffer.toNativeData()
val nsData: NSData = nativeData.nsData
NSFileManager.defaultManager.createFileAtPath(path, nsData, null)

// Get mutable NSMutableData
val mutableData = buffer.toMutableNativeData()
val nsMutableData: NSMutableData = mutableData.nsMutableData
```

### Zero-Copy Behavior

| Conversion | ByteArrayBuffer | MutableDataBuffer |
|------------|-----------------|-------------------|
| `toNativeData()` | Copy | Zero-copy (subdataWithRange) |
| `toMutableNativeData()` | Copy | Zero-copy (full buffer only) |
| `toByteArray()` | Zero-copy (backing array) | Copy |

:::note Partial Views
For `toNativeData()`, `subdataWithRange` creates a zero-copy view of the remaining bytes.

For `toMutableNativeData()`, zero-copy is only possible when position is 0 and the entire buffer is used. Partial views require a copy because `NSMutableData` needs mutable ownership of its memory.
:::

### Wrapping NSData

You can also create buffers from existing NSData:

```kotlin
// Wrap NSData (read-only, zero-copy)
val nsData: NSData = // ... from API ...
val buffer = PlatformBuffer.wrap(nsData)

// Wrap NSMutableData (mutable, zero-copy)
val nsMutableData: NSMutableData = // ... from API ...
val buffer = PlatformBuffer.wrap(nsMutableData)
```

See [Platform Interop](/docs/recipes/platform-interop) for more details.

## Best Practices

1. **Use Direct (default)** - NSMutableData is efficient
2. **No manual cleanup needed** - Kotlin GC and ARC work together
3. **Pool for hot paths** - reduce allocation overhead
