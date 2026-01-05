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

## Best Practices

1. **Use Direct (default)** - NSMutableData is efficient
2. **No manual cleanup needed** - Kotlin GC and ARC work together
3. **Pool for hot paths** - reduce allocation overhead
