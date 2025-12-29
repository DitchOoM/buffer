---
sidebar_position: 4
title: Protocol Parsing
---

# Protocol Parsing

Patterns for implementing high-performance protocol parsers.

## Parser Architecture

A typical protocol parser:

```kotlin
class ProtocolParser(private val pool: BufferPool) {
    private val processor = StreamProcessor.create(pool)

    fun append(data: ReadBuffer) {
        processor.append(data)
    }

    fun parseMessages(handler: (Message) -> Unit) {
        while (processor.available() >= HEADER_SIZE) {
            val message = tryParseMessage() ?: break
            handler(message)
        }
    }

    private fun tryParseMessage(): Message? {
        // Peek at header without consuming
        val type = processor.peekByte()
        val length = processor.peekInt(offset = 1)

        if (processor.available() < HEADER_SIZE + length) {
            return null  // Wait for more data
        }

        // Now consume
        processor.skip(HEADER_SIZE)
        val payload = processor.readBuffer(length)

        return Message(type, payload)
    }

    fun release() = processor.release()
}
```

## Length-Prefixed Protocol

Most binary protocols use length prefixes:

![Protocol Frame Format](/img/protocol-frame.svg)

```kotlin
fun parsePacket(): Packet? {
    if (processor.available() < 5) return null

    val type = processor.peekByte()
    val length = processor.peekInt(offset = 1)

    val totalSize = 5 + length
    if (processor.available() < totalSize) return null

    processor.skip(5)  // Consume header
    val payload = processor.readBuffer(length)

    return Packet(type, payload)
}
```

## Variable-Length Encoding

Some protocols (MQTT, Protocol Buffers) use variable-length integers:

```kotlin
fun peekVariableInt(): Pair<Int, Int>? {  // value to bytesUsed
    var value = 0
    var multiplier = 1
    var bytesRead = 0

    while (bytesRead < 4) {
        if (processor.available() <= bytesRead) return null

        val byte = processor.peekByte(bytesRead).toInt() and 0xFF
        value += (byte and 0x7F) * multiplier
        bytesRead++

        if ((byte and 0x80) == 0) {
            return value to bytesRead
        }
        multiplier *= 128
    }

    throw IllegalStateException("Malformed variable int")
}
```

## Magic Byte Detection

Detect file/protocol formats by their magic bytes:

```kotlin
val GZIP_MAGIC = PlatformBuffer.wrap(byteArrayOf(0x1f, 0x8b.toByte()))
val PNG_MAGIC = PlatformBuffer.wrap(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
val HTTP_GET = "GET ".toReadBuffer()  // No intermediate ByteArray

fun detectFormat(): Format {
    return when {
        processor.peekMatches(GZIP_MAGIC) -> Format.GZIP
        processor.peekMatches(PNG_MAGIC) -> Format.PNG
        processor.peekMatches(HTTP_GET) -> Format.HTTP
        else -> Format.UNKNOWN
    }
}
```

## Text Line Parsing

For line-based protocols (HTTP headers, SMTP):

```kotlin
fun readLine(): String? {
    for (i in 0 until processor.available() - 1) {
        if (processor.peekByte(i) == '\r'.code.toByte() &&
            processor.peekByte(i + 1) == '\n'.code.toByte()) {

            val lineBuffer = processor.readBuffer(i)
            processor.skip(2)  // Skip CRLF
            return lineBuffer.readString(i)
        }
    }
    return null  // No complete line yet
}

// HTTP header parsing
fun parseHeaders(): Map<String, String> {
    val headers = mutableMapOf<String, String>()

    while (true) {
        val line = readLine() ?: break
        if (line.isEmpty()) break  // End of headers

        val colonIndex = line.indexOf(':')
        if (colonIndex > 0) {
            val name = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            headers[name] = value
        }
    }

    return headers
}
```

## Zero-Copy Parsing

Maximize performance by avoiding copies:

```kotlin
// DON'T: Copy to ByteArray for parsing
val bytes = processor.readByteArray(length)
val text = bytes.decodeToString()

// DO: Read directly as string
val text = processor.readBuffer(length).let { buf ->
    buf.readString(length)
}

// DON'T: Create intermediate buffers
val header = ByteArray(4)
processor.readBytes(header)
val length = (header[0].toInt() shl 24) or ...

// DO: Read directly
val length = processor.readInt()
```

## Handling Fragmentation

Always assume data can be fragmented:

```kotlin
fun processStream(dataChannel: ReceiveChannel<ReadBuffer>) {
    val parser = ProtocolParser(pool)

    try {
        for (chunk in dataChannel) {
            parser.append(chunk)

            // Parse all complete messages
            parser.parseMessages { message ->
                handleMessage(message)
            }
        }
    } finally {
        parser.release()
    }
}
```

## Sealed Classes for Message Types

Type-safe message representation:

```kotlin
sealed interface Message {
    val type: Byte
}

data class ConnectMessage(
    val version: Int,
    val clientId: String
) : Message {
    override val type: Byte = 0x01
}

data class PublishMessage(
    val topic: String,
    val payload: ReadBuffer
) : Message {
    override val type: Byte = 0x02
}

data class DisconnectMessage(
    val reason: Int
) : Message {
    override val type: Byte = 0x03
}

fun parseMessage(): Message? {
    if (processor.available() < 1) return null

    return when (processor.peekByte()) {
        0x01.toByte() -> parseConnect()
        0x02.toByte() -> parsePublish()
        0x03.toByte() -> parseDisconnect()
        else -> throw IllegalStateException("Unknown message type")
    }
}
```

## Performance Tips

1. **Use peek before consuming** - avoid backtracking
2. **Batch processing** - parse multiple messages per append
3. **Pool buffers** - reuse for payload extraction
4. **Inline hot paths** - avoid virtual calls in tight loops
5. **Use primitive operations** - `peekInt()` over 4x `peekByte()`

## Complete Example: Simple Protocol

```kotlin
/**
 * Protocol format:
 * - 1 byte: message type
 * - 4 bytes: payload length (big-endian)
 * - N bytes: payload
 */
class SimpleProtocolParser(pool: BufferPool) {
    private val processor = StreamProcessor.create(pool)

    fun append(data: ReadBuffer) = processor.append(data)

    fun parseAll(): List<SimpleMessage> {
        val messages = mutableListOf<SimpleMessage>()

        while (true) {
            val msg = tryParse() ?: break
            messages.add(msg)
        }

        return messages
    }

    private fun tryParse(): SimpleMessage? {
        if (processor.available() < 5) return null

        val type = MessageType.fromByte(processor.peekByte())
        val length = processor.peekInt(offset = 1)

        if (processor.available() < 5 + length) return null

        processor.skip(5)
        val payload = if (length > 0) processor.readBuffer(length) else null

        return SimpleMessage(type, payload)
    }

    fun release() = processor.release()
}

enum class MessageType(val code: Byte) {
    PING(0x01),
    PONG(0x02),
    DATA(0x03);

    companion object {
        fun fromByte(b: Byte) = entries.first { it.code == b }
    }
}

data class SimpleMessage(
    val type: MessageType,
    val payload: ReadBuffer?
)
```
