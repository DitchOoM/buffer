---
sidebar_position: 1
title: Buffer Basics
---

import BufferVisualizer from '@site/src/components/BufferVisualizer';

# Buffer Basics

Understanding position, limit, and capacity is essential for working with buffers effectively.

## Interactive Buffer Visualizer

Try it yourself! Write characters or strings, call `resetForRead()`, then read them back:

<BufferVisualizer capacity={10} showChars interactive />

## Buffer State

Every buffer has three key properties:

- **Position**: Current read/write location (0 to limit-1)
- **Limit**: Boundary for read/write operations (0 to capacity)
- **Capacity**: Total buffer size (fixed at allocation)

## Write Mode vs Read Mode

### After Allocation (Write Mode)

```kotlin
val buffer = PlatformBuffer.allocate(10)
// position=0, limit=10, capacity=10
```

<BufferVisualizer
  capacity={10}
  initialPosition={0}
  initialLimit={10}
  initialMode="write"
  title="After allocation: ready to write"
/>

### After Writing

```kotlin
buffer.writeString("HELLO")
// position=5, limit=10, capacity=10
```

<BufferVisualizer
  capacity={10}
  initialData="HELLO"
  initialPosition={5}
  initialLimit={10}
  initialMode="write"
  showChars
  title="After writeString('HELLO'): position advanced to 5"
/>

### After resetForRead() (Read Mode)

```kotlin
buffer.resetForRead()
// position=0, limit=5, capacity=10
```

<BufferVisualizer
  capacity={10}
  initialData="HELLO"
  initialPosition={0}
  initialLimit={5}
  initialMode="read"
  showChars
  title="After resetForRead(): ready to read 'HELLO'"
/>

## Key Properties

```kotlin
val buffer = PlatformBuffer.allocate(1024)

buffer.capacity    // Total size (1024)
buffer.position()  // Current position
buffer.limit()     // Current limit
buffer.remaining() // limit - position (bytes available)
```

## Manipulating Position and Limit

```kotlin
// Set position directly
buffer.position(100)

// Set limit
buffer.setLimit(500)

// Move position relative to current
buffer.position(buffer.position() + 10)
```

## Interfaces

Buffer uses three main interfaces:

### ReadBuffer

Read operations without modifying content:

```kotlin
val buffer: ReadBuffer = ...
val byte = buffer.readByte()
val int = buffer.readInt()
val slice = buffer.slice()  // Create a view
```

### WriteBuffer

Write operations:

```kotlin
val buffer: WriteBuffer = ...
buffer.writeByte(0x42)
buffer.writeInt(12345)
buffer.write(otherBuffer)
```

### PlatformBuffer

Combines both interfaces with platform-specific extensions:

```kotlin
val buffer: PlatformBuffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.resetForRead()
val value = buffer.readInt()
```

## Slicing

Create a view into a buffer without copying:

```kotlin
val original = PlatformBuffer.allocate(10)
original.writeString("HELLOWORLD")
original.resetForRead()

// Create a slice of bytes 2-7 ("LLOWO")
original.position(2)
original.setLimit(7)
val slice = original.slice()

// slice shares the same memory
// Modifications to slice affect original
```

<BufferVisualizer
  capacity={10}
  initialData="HELLOWORLD"
  initialPosition={2}
  initialLimit={7}
  initialMode="read"
  showChars
  sliceFrom={2}
  sliceTo={7}
  title="Slice from position 2 to limit 7"
/>

**Why slicing matters:**
- **Zero allocation** - No new memory is allocated
- **O(1) operation** - Constant time regardless of size
- **Zero-copy** - Data is never copied, just a new view
- **Memory efficient** - Perfect for parsing protocols where you need sub-ranges

## Absolute vs Relative Operations

### Relative (modifies position)

```kotlin
buffer.writeInt(42)      // Writes at position, advances by 4
buffer.readInt()         // Reads at position, advances by 4
```

### Absolute (position unchanged)

```kotlin
buffer[10] = 0x42.toByte()  // Write at index 10
val byte = buffer[10]       // Read from index 10
val int = buffer.getInt(10) // Read int at index 10
```

## Best Practices

1. **Always call `resetForRead()`** after writing, before reading
2. **Use `remaining()`** to check available bytes before reading
3. **Prefer slicing** over copying for zero-copy performance
4. **Use buffer pools** in hot paths to avoid allocation overhead
