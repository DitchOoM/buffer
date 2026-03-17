---
sidebar_position: 3
title: Byte Order
---

import BufferVisualizer from '@site/src/components/BufferVisualizer';

# Byte Order

Byte order (endianness) determines how multi-byte values are stored in memory.

## Big-Endian vs Little-Endian

Consider the integer `0x12345678` stored in a 4-byte buffer:

<BufferVisualizer
  capacity={4}
  initialData={[0x12, 0x34, 0x56, 0x78]}
  initialPosition={4}
  initialLimit={4}
  byteOrder="big"
  hideMarkers
  hideRemaining
  subtitle="Most significant byte first (network byte order)"
/>

<BufferVisualizer
  capacity={4}
  initialData={[0x78, 0x56, 0x34, 0x12]}
  initialPosition={4}
  initialLimit={4}
  byteOrder="little"
  hideMarkers
  hideRemaining
  subtitle="Least significant byte first (x86/x64 native)"
/>

## Specifying Byte Order

The default byte order is `ByteOrder.NATIVE` (matches the CPU's native endianness). Specify a different byte order when needed:

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder

// Big-endian (network byte order)
val bigEndian = BufferFactory.Default.allocate(
    size = 1024,
    byteOrder = ByteOrder.BIG_ENDIAN
)

// Little-endian
val littleEndian = BufferFactory.Default.allocate(
    size = 1024,
    byteOrder = ByteOrder.LITTLE_ENDIAN
)
```

## Checking Byte Order

```kotlin
val buffer = BufferFactory.Default.allocate(1024)
println(buffer.byteOrder)  // NATIVE (matches CPU endianness)
```

## When to Use Each

### Big-Endian (BIG_ENDIAN)

- **Network protocols**: TCP/IP, HTTP, TLS
- **File formats**: PNG, JPEG, PDF

```kotlin
// Network packet parsing
val buffer = BufferFactory.Default.allocate(1024, ByteOrder.BIG_ENDIAN)
buffer.writeShort(80)  // Port number in network byte order
```

### Little-Endian (LITTLE_ENDIAN)

- **x86/x64 native**: Intel/AMD processors
- **Windows file formats**: BMP, WAV
- **Some protocols**: USB, PCIe

```kotlin
// Windows BMP file parsing
val buffer = BufferFactory.Default.allocate(1024, ByteOrder.LITTLE_ENDIAN)
buffer.writeInt(fileSize)
```

## Common Protocol Byte Orders

| Protocol/Format | Byte Order |
|-----------------|------------|
| TCP/IP | Big-Endian |
| HTTP | Big-Endian |
| MQTT | Big-Endian |
| WebSocket | Big-Endian |
| USB | Little-Endian |
| BMP | Little-Endian |
| WAV | Little-Endian |
| Protocol Buffers | Little-Endian (varint) |

## Example: Protocol Parsing

```kotlin
// Network protocols use big-endian
val buffer = BufferFactory.Default.allocate(1024, ByteOrder.BIG_ENDIAN)

// Write a length-prefixed message
buffer.writeByte(0x01)  // message type
buffer.writeShort(payloadLength.toShort())
buffer.writeString(payload)
```

## Mixing Byte Orders

If you need to read/write different byte orders in the same buffer, read the largest primitive and swap bytes:

```kotlin
// Read a little-endian int from a big-endian buffer
// Uses one memory read (readInt) instead of 4 separate readByte() calls
fun ReadBuffer.readLittleEndianInt(): Int {
    val bigEndian = readInt()
    return ((bigEndian and 0xFF) shl 24) or
           ((bigEndian and 0xFF00) shl 8) or
           ((bigEndian and 0xFF0000) ushr 8) or
           ((bigEndian and 0xFF000000.toInt()) ushr 24)
}

// Similarly for Long (swap all 8 bytes)
fun ReadBuffer.readLittleEndianLong(): Long {
    val bigEndian = readLong()
    return ((bigEndian and 0xFF) shl 56) or
           ((bigEndian and 0xFF00) shl 40) or
           ((bigEndian and 0xFF0000) shl 24) or
           ((bigEndian and 0xFF000000) shl 8) or
           ((bigEndian and 0xFF00000000) ushr 8) or
           ((bigEndian and 0xFF0000000000) ushr 24) or
           ((bigEndian and 0xFF000000000000) ushr 40) or
           ((bigEndian ushr 56) and 0xFF)
}
```

## Best Practices

1. **Use big-endian for network code** - it's the standard
2. **Match the protocol spec** - always check documentation
3. **Be explicit** - specify byte order when it matters (default is `ByteOrder.NATIVE`)
4. **Document byte order** - especially at API boundaries
5. **Read largest primitive** - swap bytes after rather than reading byte-by-byte
