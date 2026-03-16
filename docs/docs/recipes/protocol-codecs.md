---
sidebar_position: 5
title: Protocol Codecs
---

# Protocol Codecs

The `buffer-codec` module provides a structured codec system for encoding and decoding binary protocol messages. Instead of writing ad-hoc parsers that manually read fields one by one, you define a `Codec<T>` that pairs encode and decode logic together, making your protocol implementations type-safe and testable.

## Why Protocol Codecs?

Hand-written protocol parsers work fine for simple cases, but they become error-prone as protocols grow:

- **Field order mismatches** between encode and decode go undetected until runtime
- **Missing fields** in one direction cause subtle data corruption
- **No round-trip guarantee** without manual test discipline
- **Duplicated logic** when the same struct appears in multiple message types

The codec system solves these problems by:

- Pairing encode and decode in a single `Codec<T>` interface
- Providing `testRoundTrip()` to verify correctness with one call
- Operating directly on `ReadBuffer`/`WriteBuffer` for zero-overhead access

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-codec:<latest-version>")
}
```

## Quick Start

Define a data class for your protocol struct and implement a `Codec` for it:

```kotlin
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec

data class SensorReading(
    val sensorId: UShort,
    val temperature: Int,
)

object SensorReadingCodec : Codec<SensorReading> {
    override fun decode(buffer: ReadBuffer): SensorReading =
        SensorReading(
            sensorId = buffer.readUnsignedShort(),
            temperature = buffer.readInt(),
        )

    override fun encode(buffer: WriteBuffer, value: SensorReading) {
        buffer.writeUShort(value.sensorId)
        buffer.writeInt(value.temperature)
    }

    override fun sizeOf(value: SensorReading): Int = 6 // 2 + 4 bytes
}
```

Encode and decode:

```kotlin
import com.ditchoom.buffer.codec.encodeToBuffer

// Encode to a new buffer
val buffer = SensorReadingCodec.encodeToBuffer(reading)

// Decode from a ReadBuffer
val decoded = SensorReadingCodec.decode(buffer)

// Encode into an existing WriteBuffer
SensorReadingCodec.encode(existingBuffer, reading)
```

## The Codec Interface

The core interface is minimal:

```kotlin
interface Codec<T> {
    fun decode(buffer: ReadBuffer): T
    fun encode(buffer: WriteBuffer, value: T)
    fun sizeOf(value: T): Int? = null
}
```

- **`decode`** reads fields from a `ReadBuffer` and constructs the value
- **`encode`** writes fields to a `WriteBuffer`
- **`sizeOf`** returns the encoded byte size if known, or `null` for variable-length encodings

Codecs operate directly on `ReadBuffer`/`WriteBuffer` with no wrapper overhead.

## Length-Prefixed Strings

Use the `readLengthPrefixedUtf8String()` and `writeLengthPrefixedUtf8String()` extension functions for MQTT-style length-prefixed strings: a 2-byte big-endian length prefix followed by UTF-8 data.

```kotlin
import com.ditchoom.buffer.readLengthPrefixedUtf8String
import com.ditchoom.buffer.writeLengthPrefixedUtf8String

data class ConnectMessage(val clientId: String, val username: String)

object ConnectMessageCodec : Codec<ConnectMessage> {
    override fun decode(buffer: ReadBuffer): ConnectMessage =
        ConnectMessage(
            clientId = buffer.readLengthPrefixedUtf8String().second,
            username = buffer.readLengthPrefixedUtf8String().second,
        )

    override fun encode(buffer: WriteBuffer, value: ConnectMessage) {
        buffer.writeLengthPrefixedUtf8String(value.clientId)
        buffer.writeLengthPrefixedUtf8String(value.username)
    }
}
```

## Variable-Length Integers

The `readVariableByteInteger()` and `writeVariableByteInteger()` extension functions handle MQTT-style variable-byte integers (1-4 bytes, max value 268,435,455). Each byte uses 7 bits for data and 1 bit as a continuation flag.

```kotlin
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.writeVariableByteInteger

data class PublishHeader(val topicLength: Int, val payloadLength: Int)

object PublishHeaderCodec : Codec<PublishHeader> {
    override fun decode(buffer: ReadBuffer): PublishHeader =
        PublishHeader(
            topicLength = buffer.readVariableByteInteger(),
            payloadLength = buffer.readVariableByteInteger(),
        )

    override fun encode(buffer: WriteBuffer, value: PublishHeader) {
        buffer.writeVariableByteInteger(value.topicLength)
        buffer.writeVariableByteInteger(value.payloadLength)
    }
}
```

## Composing Codecs

Codecs compose naturally. Use one codec inside another to decode nested structures:

```kotlin
data class Envelope(val version: UByte, val sensor: SensorReading)

object EnvelopeCodec : Codec<Envelope> {
    override fun decode(buffer: ReadBuffer): Envelope =
        Envelope(
            version = buffer.readUnsignedByte(),
            sensor = SensorReadingCodec.decode(buffer),
        )

    override fun encode(buffer: WriteBuffer, value: Envelope) {
        buffer.writeUByte(value.version)
        SensorReadingCodec.encode(buffer, value.sensor)
    }

    override fun sizeOf(value: Envelope): Int? {
        val sensorSize = SensorReadingCodec.sizeOf(value.sensor) ?: return null
        return 1 + sensorSize
    }
}
```

## Message Dispatch with Sealed Interfaces

For protocols with multiple message types distinguished by a type byte, combine sealed interfaces with codec dispatch:

```kotlin
sealed interface Message {
    val typeCode: Byte
}

data class PingMessage(val timestamp: Long) : Message {
    override val typeCode: Byte = 0x01
}

data class DataMessage(val payload: String) : Message {
    override val typeCode: Byte = 0x02
}

object PingCodec : Codec<PingMessage> {
    override fun decode(buffer: ReadBuffer) =
        PingMessage(buffer.readLong())

    override fun encode(buffer: WriteBuffer, value: PingMessage) {
        buffer.writeLong(value.timestamp)
    }
}

object DataCodec : Codec<DataMessage> {
    override fun decode(buffer: ReadBuffer) =
        DataMessage(buffer.readLengthPrefixedUtf8String().second)

    override fun encode(buffer: WriteBuffer, value: DataMessage) {
        buffer.writeLengthPrefixedUtf8String(value.payload)
    }
}

object MessageCodec : Codec<Message> {
    override fun decode(buffer: ReadBuffer): Message {
        return when (val type = buffer.readByte()) {
            0x01.toByte() -> PingCodec.decode(buffer)
            0x02.toByte() -> DataCodec.decode(buffer)
            else -> throw IllegalArgumentException("Unknown type: $type")
        }
    }

    override fun encode(buffer: WriteBuffer, value: Message) {
        buffer.writeByte(value.typeCode)
        when (value) {
            is PingMessage -> PingCodec.encode(buffer, value)
            is DataMessage -> DataCodec.encode(buffer, value)
        }
    }
}
```

## PayloadReader

When your protocol has a length-delimited payload section that you want to hand off to application code, use `PayloadReader`:

```kotlin
val payloadReader = ReadBufferPayloadReader(payloadBuffer)

// Read structured data from the payload
val id = payloadReader.readInt()
val name = payloadReader.readString(nameLength)

// Or copy the entire payload to a new buffer
val copy = payloadReader.copyToBuffer(BufferFactory.managed())

// Or transfer bytes to a WriteBuffer
payloadReader.transferTo(writeBuffer)
```

`PayloadReader` tracks whether it has been released, preventing use-after-release bugs:

```kotlin
val reader = ReadBufferPayloadReader(buffer)
// ... use reader ...
reader.release()
reader.readByte()  // throws IllegalStateException
```

## Streaming Integration

For streaming scenarios where data arrives in chunks, use `StreamProcessor` to accumulate bytes, then read contiguous buffers for codec decoding:

```kotlin
withPool { pool ->
    val processor = StreamProcessor.create(pool)

    // Feed chunks as they arrive from the network
    for (chunk in networkChannel) {
        processor.append(chunk)

        // Parse complete messages when enough data is available
        while (processor.available() >= HEADER_SIZE) {
            val headerBuffer = processor.readBuffer(HEADER_SIZE)
            val message = MessageCodec.decode(headerBuffer)
            handleMessage(message)
        }
    }
}
```

## Round-Trip Testing

The `testRoundTrip` extension function verifies that encode followed by decode produces the original value:

```kotlin
// Basic round-trip test
val decoded = SensorReadingCodec.testRoundTrip(
    SensorReading(42u.toUShort(), 123456)
)

// With expected wire bytes
val decoded = SensorReadingCodec.testRoundTrip(
    SensorReading(1u.toUShort(), 2),
    expectedBytes = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x02)
)
```

This is especially useful in unit tests:

```kotlin
@Test
fun sensorReadingRoundTrip() {
    val original = SensorReading(42u.toUShort(), -17)
    val decoded = SensorReadingCodec.testRoundTrip(original)
    assertEquals(original, decoded)
}
```

## Extension Functions Reference

The `buffer-codec` module provides convenience extensions on `Codec<T>`:

| Function | Description |
|----------|-------------|
| `codec.encodeToBuffer(value)` | Allocate a new buffer, encode, and return it ready for reading |
| `codec.testRoundTrip(value)` | Encode then decode, returning the decoded value |
| `codec.testRoundTrip(value, expectedBytes)` | Same, but also verifies the wire bytes match |

## Complete Example: Simple Binary Protocol

```kotlin
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.readLengthPrefixedUtf8String
import com.ditchoom.buffer.writeLengthPrefixedUtf8String

/**
 * Protocol format:
 * - 1 byte:  message type
 * - 2 bytes: sequence number (UShort)
 * - 4 bytes: payload length (Int)
 * - N bytes: payload (length-prefixed UTF-8 string)
 */
data class ProtocolMessage(
    val type: UByte,
    val sequence: UShort,
    val payload: String,
)

object ProtocolMessageCodec : Codec<ProtocolMessage> {
    override fun decode(buffer: ReadBuffer): ProtocolMessage =
        ProtocolMessage(
            type = buffer.readUnsignedByte(),
            sequence = buffer.readUnsignedShort(),
            payload = buffer.readLengthPrefixedUtf8String().second,
        )

    override fun encode(buffer: WriteBuffer, value: ProtocolMessage) {
        buffer.writeUByte(value.type)
        buffer.writeUShort(value.sequence)
        buffer.writeLengthPrefixedUtf8String(value.payload)
    }

    override fun sizeOf(value: ProtocolMessage): Int {
        val payloadBytes = value.payload.encodeToByteArray().size
        return 1 + 2 + 2 + payloadBytes  // type + seq + length prefix + string
    }
}

// Usage
fun main() {
    val message = ProtocolMessage(
        type = 0x01u,
        sequence = 42u.toUShort(),
        payload = "Hello, Protocol!"
    )

    // Encode
    val buffer = ProtocolMessageCodec.encodeToBuffer(message)

    // Decode
    val decoded = ProtocolMessageCodec.decode(buffer)
    println(decoded) // ProtocolMessage(type=1, sequence=42, payload=Hello, Protocol!)

    // Verify round-trip in tests
    ProtocolMessageCodec.testRoundTrip(message)
}
```

## Code Generation with @ProtocolMessage

Instead of writing codecs by hand, annotate your data class and let the KSP processor generate `decode`, `encode`, and `sizeOf` at compile time.

> **Note:** Generated code appears after compilation (`./gradlew build`). IDE features like autocomplete and navigation for generated codecs require an initial build.

### Installation

Add the KSP plugin and the processor dependency:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("com.ditchoom:buffer-codec:<latest-version>")
    ksp("com.ditchoom:buffer-codec-processor:<latest-version>")
}
```

### Basic Example

```kotlin
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

@ProtocolMessage
data class SensorReading(
    val sensorId: UShort,    // 2 bytes
    val temperature: Int,    // 4 bytes
)
```

The processor generates `SensorReadingCodec`:

```kotlin
// Generated — don't edit
object SensorReadingCodec : Codec<SensorReading> {
    override fun decode(buffer: ReadBuffer): SensorReading =
        SensorReading(
            sensorId = buffer.readUnsignedShort(),
            temperature = buffer.readInt(),
        )

    override fun encode(buffer: WriteBuffer, value: SensorReading) {
        buffer.writeUShort(value.sensorId)
        buffer.writeInt(value.temperature)
    }

    override fun sizeOf(value: SensorReading): Int? = 6
}
```

Use it exactly like a hand-written codec:

```kotlin
val buffer = SensorReadingCodec.encodeToBuffer(reading)
val decoded = SensorReadingCodec.decode(buffer)
SensorReadingCodec.testRoundTrip(reading) // round-trip verification
```

### Annotation Reference

#### `@LengthPrefixed` — Length-Prefixed Strings

Reads/writes a string with a byte-length prefix. Default prefix is 2-byte big-endian (`LengthPrefix.Short`).

```kotlin
@ProtocolMessage
data class GreetingMessage(
    @LengthPrefixed val name: String,                          // 2-byte prefix (default)
    @LengthPrefixed(LengthPrefix.Byte) val nickname: String,   // 1-byte prefix (max 255 bytes)
    @LengthPrefixed(LengthPrefix.Int) val bio: String,         // 4-byte prefix
)
```

#### `@RemainingBytes` — Consume Remaining Bytes

Reads all remaining bytes as a UTF-8 string. Must be the last constructor parameter.

```kotlin
@ProtocolMessage
data class LogEntry(
    val level: UByte,
    @RemainingBytes val message: String,  // reads everything after level byte
)
```

#### `@LengthFrom("field")` — Length from Preceding Field

The string's byte length is determined by a preceding numeric field instead of a prefix in the wire format.

```kotlin
@ProtocolMessage
data class NamedRecord(
    val nameLength: UShort,
    @LengthFrom("nameLength") val name: String,  // reads nameLength bytes as UTF-8
    val value: Int,
)
```

#### `@WireBytes(n)` — Custom Wire Width

Override the default wire size of a numeric field. Useful for protocols that use 3-byte, 5-byte, 6-byte, or 7-byte integers.

```kotlin
@ProtocolMessage
data class CompactHeader(
    @WireBytes(3) val length: Int,    // 3 bytes on the wire, read into Int
    @WireBytes(6) val offset: Long,   // 6 bytes on the wire, read into Long
)
```

Only applies to integer types (`Byte`, `UByte`, `Short`, `UShort`, `Int`, `UInt`, `Long`, `ULong`). Not allowed on `Float`, `Double`, or `Boolean`.

#### `@WhenTrue("expression")` — Conditional Fields

A field that is only present on the wire when a preceding Boolean field is `true`. The annotated field must be nullable with a default of `null`.

```kotlin
@ProtocolMessage
data class OptionalPayload(
    val hasExtra: Boolean,
    @WhenTrue("hasExtra") val extra: Int? = null,  // only read/written when hasExtra == true
)
```

#### `@Payload` + Type Parameter — Generic Payloads

Mark a type parameter with `@Payload` to create a codec that is generic over its payload. A context class is generated to carry the non-payload fields needed for payload decoding.

```kotlin
@ProtocolMessage
data class Packet<@Payload P>(
    val version: UByte,
    val payloadLength: UShort,
    val payload: P,
)
```

#### `@PacketType` + Sealed Interfaces — Auto-Dispatched Decode

Annotate a sealed interface with `@ProtocolMessage` and each variant with `@PacketType(value)` to generate a dispatch codec that reads the type discriminator and delegates to the correct variant codec. The value must be 0-255 (single byte on the wire). Duplicate values across variants are rejected at compile time.

```kotlin
@ProtocolMessage
sealed interface Command {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ping(val timestamp: Long) : Command

    @ProtocolMessage
    @PacketType(0x02)
    data class Echo(
        @LengthPrefixed val message: String,
    ) : Command
}
```

The processor generates:
- `PingCodec` and `EchoCodec` for each variant
- `CommandCodec` that reads one byte, dispatches to the correct variant codec, and writes the type byte + variant on encode

```kotlin
val cmd: Command = CommandCodec.decode(buffer) // reads type byte, dispatches automatically
CommandCodec.encode(outputBuffer, Command.Ping(System.currentTimeMillis()))
```

### Value Classes — Zero-Overhead Typed Wrappers

Value classes wrapping a primitive type are supported as fields. The generated codec reads/writes the inner primitive directly with no boxing overhead:

```kotlin
@JvmInline
value class PacketId(val raw: UShort)

@JvmInline
value class ConnAckFlags(val raw: UByte) {
    val sessionPresent: Boolean get() = raw.toInt() and 1 == 1
}

@ProtocolMessage
data class ConnAck(
    val flags: ConnAckFlags,   // reads 1 byte, wraps in ConnAckFlags
    val packetId: PacketId,    // reads 2 bytes, wraps in PacketId
)
```

This is the recommended pattern for protocol-specific types — you get type safety at the Kotlin level while the wire representation stays as a raw primitive.

### Supported Types

The processor handles all Kotlin primitive types:

| Kotlin Type | Default Wire Size | Signed |
|-------------|------------------|--------|
| `Byte` | 1 | Yes |
| `UByte` | 1 | No |
| `Short` | 2 | Yes |
| `UShort` | 2 | No |
| `Int` | 4 | Yes |
| `UInt` | 4 | No |
| `Long` | 8 | Yes |
| `ULong` | 8 | No |
| `Float` | 4 | — |
| `Double` | 8 | — |
| `Boolean` | 1 | — |
| `String` | Variable | — |

### Batch Optimization

The generated code automatically groups consecutive fixed-size fields into single bulk reads where possible (e.g., reading a `Long` and splitting it into two `Int` fields), reducing the number of read/write calls in the hot path.

### Custom Annotations (SPI)

When the built-in annotations don't cover your protocol's encoding (e.g., variable-byte integers,
repeated fields, TLV property bags), you can register custom annotations via the codec SPI.

The SPI lets you:
- Define your own annotation (e.g., `@VariableByteInteger`)
- Write the read/write/sizeOf functions in plain Kotlin
- Register a `CodecFieldProvider` that maps the annotation to those functions
- The generated codec calls your functions — type-safe, debuggable, no string templates

#### Step 1: Define Your Annotation

```kotlin
package com.example.myprotocol.annotations

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class VariableByteInteger
```

#### Step 2: Write Your Functions

Write `ReadBuffer`/`WriteBuffer` extension functions that handle the encoding:

```kotlin
package com.example.myprotocol.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

fun ReadBuffer.readVariableByteInteger(): Int { /* ... */ }
fun WriteBuffer.writeVariableByteInteger(value: Int) { /* ... */ }
fun variableByteSizeInt(value: Int): Int { /* ... */ }
```

These are real Kotlin functions — debuggable, testable, IDE-navigable.

#### Step 3: Create a CodecFieldProvider

```kotlin
package com.example.myprotocol.spi

import com.ditchoom.buffer.codec.processor.spi.*

class VariableByteIntegerProvider : CodecFieldProvider {
    override val annotationFqn = "com.example.myprotocol.annotations.VariableByteInteger"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        require(context.typeName == "kotlin.Int") {
            "@VariableByteInteger can only be applied to Int fields"
        }
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.example.myprotocol.codec", "readVariableByteInteger"),
            writeFunction = FunctionRef("com.example.myprotocol.codec", "writeVariableByteInteger"),
            sizeOfFunction = FunctionRef("com.example.myprotocol.codec", "variableByteSizeInt"),
        )
    }
}
```

#### Step 4: Create a KSP Provider Module

Custom providers run at **build time** on the KSP processor classpath. Create a JVM-only module:

```kotlin
// my-protocol-ksp/build.gradle.kts
plugins {
    kotlin("jvm")
}
dependencies {
    implementation("com.ditchoom:buffer-codec-processor:<version>")
}
```

Register via `META-INF/services/com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider`:

```
com.example.myprotocol.spi.VariableByteIntegerProvider
```

#### Step 5: Wire KSP Dependencies

In your protocol module's `build.gradle.kts`:

```kotlin
dependencies {
    ksp("com.ditchoom:buffer-codec-processor:<version>")
    ksp(project(":my-protocol-ksp"))  // your providers
}
```

The KSP classloader merges both JARs, so `ServiceLoader` discovers your providers automatically.

#### Step 6: Use It

```kotlin
@ProtocolMessage
data class MqttHeader(
    val packetType: UByte,
    @VariableByteInteger val remainingLength: Int,
)
// Generates MqttHeaderCodec with buffer.readVariableByteInteger() calls
```

#### Context Fields — Dependent Parsing

Some custom fields need values from previously decoded fields. Use `contextFields` to pass them
as extra arguments to your read/write functions:

```kotlin
class RepeatedShortsProvider : CodecFieldProvider {
    override val annotationFqn = "com.example.annotations.RepeatedShorts"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        val countField = context.annotationArguments["countField"] as String
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.example.codec", "readRepeatedShorts"),
            writeFunction = FunctionRef("com.example.codec", "writeRepeatedShorts"),
            contextFields = listOf(countField),
        )
    }
}
```

Generated code passes the context field:
```kotlin
// decode: buffer.readRepeatedShorts(count)
// encode: buffer.writeRepeatedShorts(value.items, value.count)
```

#### Validation

Providers can validate field types and annotation arguments in `describe()`. Thrown exceptions
become compile-time KSP errors:

```kotlin
override fun describe(context: FieldContext): CustomFieldDescriptor {
    require(context.typeName == "kotlin.Int") {
        "@VariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
    }
    // ...
}
```

#### Restrictions

- Cannot override built-in annotations (`@LengthPrefixed`, `@WireBytes`, etc.)
- Custom fields break batch optimization (each custom field is read/written individually)
- Two providers cannot register the same annotation FQN

### Compatibility with Manual Codecs

Generated codecs implement the same `Codec<T>` interface, so all manual usage patterns apply:

- Compose with other codecs (generated or hand-written)
- Verify with `testRoundTrip()`
- Use the same `encodeToBuffer` extension function
