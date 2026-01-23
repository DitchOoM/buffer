---
sidebar_position: 1
title: Basic Operations
---

# Basic Operations

Common patterns for reading, writing, and manipulating buffers.

## Writing Primitives

### Relative Writes (advance position)

```kotlin
val buffer = PlatformBuffer.allocate(1024)

// Signed types
buffer.writeByte(42.toByte())
buffer.writeShort(1000.toShort())
buffer.writeInt(123456)
buffer.writeLong(9876543210L)
buffer.writeFloat(3.14159f)
buffer.writeDouble(2.71828182845)

// Unsigned types
buffer.writeUByte(255.toUByte())
buffer.writeUShort(65535.toUShort())
buffer.writeUInt(4294967295.toUInt())
buffer.writeULong(18446744073709551615uL)
```

### Absolute Writes (position unchanged)

```kotlin
buffer[0] = 42.toByte()
buffer[4] = 1000.toShort()
buffer[8] = 123456
buffer[16] = 9876543210L
```

## Reading Primitives

### Relative Reads (advance position)

```kotlin
buffer.resetForRead()

val byte = buffer.readByte()
val short = buffer.readShort()
val int = buffer.readInt()
val long = buffer.readLong()
val float = buffer.readFloat()
val double = buffer.readDouble()

// Unsigned
val uByte = buffer.readUnsignedByte()
val uShort = buffer.readUnsignedShort()
val uInt = buffer.readUnsignedInt()
val uLong = buffer.readUnsignedLong()
```

### Absolute Reads (position unchanged)

```kotlin
val byte = buffer[0]
val short = buffer.getShort(4)
val int = buffer.getInt(8)
val long = buffer.getLong(16)
```

## String Operations

### Writing Strings

```kotlin
// UTF-8 encoded (default)
buffer.writeString("Hello, World!")

// With explicit charset
buffer.writeString("Hello", Charset.UTF8)
```

### Reading Strings

```kotlin
// Read fixed number of bytes as UTF-8
val text = buffer.readString(13)  // "Hello, World!"

// Read with explicit charset
val text = buffer.readUtf8(bytes = 5)
```

## Byte Array Operations

### Writing Byte Arrays

```kotlin
val data = byteArrayOf(1, 2, 3, 4, 5)

// Write entire array
buffer.writeBytes(data)

// Write partial array
buffer.writeBytes(data, offset = 1, length = 3)  // writes [2, 3, 4]
```

### Reading Byte Arrays

```kotlin
// Read into new array
val data = buffer.readByteArray(5)

// Read as buffer slice (zero-copy)
val slice = buffer.readBytes(5)
```

## Buffer-to-Buffer Copy

```kotlin
val source = PlatformBuffer.allocate(100)
source.writeString("Hello")
source.resetForRead()

val dest = PlatformBuffer.allocate(100)

// Copy remaining bytes from source to dest
dest.write(source)
```

## Slicing

Create a view without copying:

```kotlin
val original = PlatformBuffer.allocate(100)
original.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
original.resetForRead()

// Slice bytes 3-7
original.position(3)
original.setLimit(8)
val slice = original.slice()

// slice now contains [4, 5, 6, 7, 8]
// Changes to slice affect original!
```

## Position and Limit Manipulation

```kotlin
val buffer = PlatformBuffer.allocate(100)

// Write some data
buffer.writeInt(1)
buffer.writeInt(2)
buffer.writeInt(3)

// Get current position
val written = buffer.position()  // 12

// Prepare for reading
buffer.resetForRead()
// position=0, limit=12

// Skip first int
buffer.position(4)

// Read remaining
val second = buffer.readInt()  // 2
val third = buffer.readInt()   // 3
```

## Checking Available Data

```kotlin
// Bytes between position and limit
val available = buffer.remaining()

// Check before reading
if (buffer.remaining() >= 4) {
    val value = buffer.readInt()
}
```

## Clearing and Resetting

```kotlin
// Reset for writing (position=0, limit=capacity)
buffer.resetForWrite()

// Reset for reading (position=0, limit=previous position)
buffer.resetForRead()
```

## Wrapping Data

```kotlin
// Wrap existing byte array (no copy)
val data = byteArrayOf(0, 0, 0, 42)
val buffer = PlatformBuffer.wrap(data)

val value = buffer.readInt()  // 42 (big-endian)

// Wrap with byte order
val littleEndian = PlatformBuffer.wrap(data, ByteOrder.LITTLE_ENDIAN)
```

## Complete Example

```kotlin
fun serializeMessage(id: Int, payload: String, buffer: WriteBuffer) {
    // Write header
    buffer.writeInt(id)

    // Write payload length, then string directly (no intermediary ByteArray)
    val lengthPosition = buffer.position()
    buffer.writeInt(0)  // Placeholder for length

    val payloadStart = buffer.position()
    buffer.writeString(payload)
    val payloadLength = buffer.position() - payloadStart

    // Go back and write actual length
    val endPosition = buffer.position()
    buffer.position(lengthPosition)
    buffer.writeInt(payloadLength)
    buffer.position(endPosition)
}

fun deserializeMessage(buffer: ReadBuffer): Pair<Int, String> {
    val id = buffer.readInt()
    val length = buffer.readInt()
    val payload = buffer.readString(length)
    return id to payload
}

// Usage with buffer pool
pool.withBuffer(1024) { buffer ->
    serializeMessage(42, "Hello, World!", buffer)
    buffer.resetForRead()
    val (id, message) = deserializeMessage(buffer)
}
```

:::tip Avoid Intermediary ByteArrays
Creating intermediate `ByteArray` copies is a code smell that hurts performance:

```kotlin
// ❌ Anti-pattern: creates temporary ByteArray
val payloadBytes = payload.encodeToByteArray()  // Allocation!
buffer.writeBytes(payloadBytes)

// ✅ Better: write string directly
buffer.writeString(payload)  // No intermediate allocation
```

The same applies when reading:
```kotlin
// ❌ Anti-pattern: copies to ByteArray first
val bytes = buffer.readByteArray(length)
processBytes(bytes)

// ✅ Better: use slice or read directly
val slice = buffer.readBytes(length)  // Zero-copy slice
```

If you find yourself frequently converting to/from `ByteArray`, consider whether you can work with the buffer directly instead.
:::
