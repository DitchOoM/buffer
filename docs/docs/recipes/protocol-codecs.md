---
sidebar_position: 3
title: Protocol Codecs
---

# Protocol Codecs

Annotate a data class, get a type-safe codec at compile time — with batch-optimized reads, round-trip testing, and zero manual field wiring.

## Why Protocol Codecs?

Hand-written protocol parsers break silently as protocols grow:

- **Field order mismatches** between encode and decode go undetected until runtime
- **Missing fields** in one direction cause subtle data corruption
- **No round-trip guarantee** without manual test discipline
- **Tedious boilerplate** — every field needs matching read/write calls in exact order

## Installation

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("com.ditchoom:buffer-codec:<latest-version>")
    ksp("com.ditchoom:buffer-codec-processor:<latest-version>")
}
```

## Quick Start

Define your wire format as a data class:

```kotlin
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.LengthPrefixed

@ProtocolMessage
data class DeviceReport(
    val protocolVersion: UByte,       // 1 byte
    val deviceType: UShort,           // 2 bytes
    val sequenceNumber: UInt,         // 4 bytes
    val timestamp: Long,              // 8 bytes
    val latitude: Double,             // 8 bytes
    val longitude: Double,            // 8 bytes
    val altitude: Float,              // 4 bytes
    val batteryLevel: UByte,          // 1 byte
    val signalStrength: Short,        // 2 bytes
    @LengthPrefixed val deviceName: String,
)
```

That's it. The KSP processor generates `DeviceReportCodec` with `decode`, `encode`, and `sizeOf` — all type-safe and batch-optimized:

```kotlin
val buffer = DeviceReportCodec.encodeToBuffer(report)
val decoded = DeviceReportCodec.decode(buffer)
DeviceReportCodec.testRoundTrip(report) // verify correctness in one call
```

> **Note:** Generated code appears after `./gradlew build`. IDE autocomplete for generated codecs requires an initial build.

### What you'd have to write manually

Without code gen, this 10-field message requires writing every field in exact order — twice. One mismatch (e.g., swapping `latitude`/`longitude`, or reading a `Float` where you wrote a `Double`) silently corrupts data:

```kotlin
// 30+ lines of error-prone boilerplate
object DeviceReportCodec : Codec<DeviceReport> {
    override fun decode(buffer: ReadBuffer) = DeviceReport(
        protocolVersion = buffer.readUnsignedByte(),
        deviceType = buffer.readUnsignedShort(),
        sequenceNumber = buffer.readUnsignedInt(),
        timestamp = buffer.readLong(),
        latitude = buffer.readDouble(),
        longitude = buffer.readDouble(),
        altitude = buffer.readFloat(),
        batteryLevel = buffer.readUnsignedByte(),
        signalStrength = buffer.readShort(),
        deviceName = buffer.readLengthPrefixedUtf8String().second,
    )

    override fun encode(buffer: WriteBuffer, value: DeviceReport) {
        buffer.writeUByte(value.protocolVersion)
        buffer.writeUShort(value.deviceType)
        buffer.writeUInt(value.sequenceNumber)
        buffer.writeLong(value.timestamp)
        buffer.writeDouble(value.latitude)
        buffer.writeDouble(value.longitude)
        buffer.writeFloat(value.altitude)
        buffer.writeUByte(value.batteryLevel)
        buffer.writeShort(value.signalStrength)
        buffer.writeLengthPrefixedUtf8String(value.deviceName)
    }

    override fun sizeOf(value: DeviceReport): Int {
        val nameBytes = value.deviceName.encodeToByteArray().size
        return 1 + 2 + 4 + 8 + 8 + 8 + 4 + 1 + 2 + 2 + nameBytes
    }
}
```

The generated version is identical but also applies **batch optimization** — consecutive fixed-size fields are grouped into bulk reads, reducing the number of read/write calls in the hot path.

### Round-Trip Testing

Verify that encode → decode produces the original value:

```kotlin
@Test
fun deviceReportRoundTrip() {
    val report = DeviceReport(
        protocolVersion = 1u,
        deviceType = 42u.toUShort(),
        sequenceNumber = 1000u,
        timestamp = 1710000000000L,
        latitude = 37.7749,
        longitude = -122.4194,
        altitude = 15.5f,
        batteryLevel = 87u,
        signalStrength = -65,
        deviceName = "sensor-north-1",
    )
    val decoded = DeviceReportCodec.testRoundTrip(report)
    assertEquals(report, decoded)
}

// Or verify exact wire bytes
DeviceReportCodec.testRoundTrip(report, expectedBytes = wireBytes)
```

## Annotation Reference

### `@LengthPrefixed` — Length-Prefixed Strings

Reads/writes a string with a byte-length prefix. Default is 2-byte big-endian.

```kotlin
@ProtocolMessage
data class GreetingMessage(
    @LengthPrefixed val name: String,                          // 2-byte prefix (default)
    @LengthPrefixed(LengthPrefix.Byte) val nickname: String,   // 1-byte prefix (max 255 bytes)
    @LengthPrefixed(LengthPrefix.Int) val bio: String,         // 4-byte prefix
)
```

### `@RemainingBytes` — Consume Remaining Bytes

Reads all remaining bytes as a UTF-8 string. Must be the last constructor parameter.

```kotlin
@ProtocolMessage
data class LogEntry(
    val level: UByte,
    @RemainingBytes val message: String,  // reads everything after level byte
)
```

### `@LengthFrom("field")` — Length from Preceding Field

The string's byte length is determined by a preceding numeric field instead of a prefix in the wire format.

```kotlin
@ProtocolMessage
data class NamedRecord(
    val nameLength: UShort,
    @LengthFrom("nameLength") val name: String,  // reads nameLength bytes as UTF-8
    val value: Int,
)
```

### `@WireBytes(n)` — Custom Wire Width

Override the default wire size of a numeric field. Useful for protocols that use 3-byte, 5-byte, 6-byte, or 7-byte integers.

```kotlin
@ProtocolMessage
data class CompactHeader(
    @WireBytes(3) val length: Int,    // 3 bytes on the wire, read into Int
    @WireBytes(6) val offset: Long,   // 6 bytes on the wire, read into Long
)
```

Only applies to integer types. Not allowed on `Float`, `Double`, or `Boolean`.

### `@WhenTrue("expression")` — Conditional Fields

A field that is only present on the wire when a preceding Boolean field is `true`. The annotated field must be nullable with a default of `null`.

```kotlin
@ProtocolMessage
data class OptionalPayload(
    val hasExtra: Boolean,
    @WhenTrue("hasExtra") val extra: Int? = null,
)
```

### `@Payload` + Type Parameter — Generic Payloads

Mark a type parameter with `@Payload` to create a codec that is generic over its payload. A context class is generated to carry the non-payload fields needed for payload decoding.

```kotlin
@ProtocolMessage
data class Packet<@Payload P>(
    val version: UByte,
    val payloadLength: UShort,
    val payload: P,
)
```

### `@PacketType` + Sealed Interfaces — Auto-Dispatched Decode

Annotate a sealed interface with `@ProtocolMessage` and each variant with `@PacketType(value)` to generate a dispatch codec. The processor reads the type discriminator and delegates to the correct variant codec. Duplicate values are rejected at compile time.

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
val cmd: Command = CommandCodec.decode(buffer)
CommandCodec.encode(outputBuffer, Command.Ping(System.currentTimeMillis()))
```

### Value Classes — Zero-Overhead Typed Wrappers

Value classes wrapping a primitive type are supported as fields. The generated codec reads/writes the inner primitive directly with no boxing overhead:

```kotlin
@JvmInline
value class PacketId(val raw: UShort)

@JvmInline
value class Flags(val raw: UByte) {
    val sessionPresent: Boolean get() = raw.toInt() and 1 == 1
}

@ProtocolMessage
data class Acknowledgement(
    val flags: Flags,       // reads 1 byte, wraps in Flags
    val packetId: PacketId, // reads 2 bytes, wraps in PacketId
)
```

### Supported Types

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

---

## Custom Annotations (SPI)

When the built-in annotations don't cover your protocol's encoding (e.g., variable-byte integers, repeated fields, TLV property bags), register custom annotations via the codec SPI.

The SPI lets you:
- Define your own annotation (e.g., `@VariableByteInteger`)
- Write the read/write/sizeOf functions in plain Kotlin
- Register a `CodecFieldProvider` that maps the annotation to those functions
- The generated codec calls your functions — type-safe, debuggable, no string templates

### Step 1: Define Your Annotation

```kotlin
package com.example.myprotocol.annotations

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class VariableByteInteger
```

### Step 2: Write Your Functions

```kotlin
package com.example.myprotocol.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

fun ReadBuffer.readVariableByteInteger(): Int { /* ... */ }
fun WriteBuffer.writeVariableByteInteger(value: Int) { /* ... */ }
fun variableByteSizeInt(value: Int): Int { /* ... */ }
```

### Step 3: Create a CodecFieldProvider

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

### Step 4: Create a KSP Provider Module

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

### Step 5: Wire KSP Dependencies

```kotlin
dependencies {
    ksp("com.ditchoom:buffer-codec-processor:<version>")
    ksp(project(":my-protocol-ksp"))  // your providers
}
```

### Step 6: Use It

```kotlin
@ProtocolMessage
data class FrameHeader(
    val packetType: UByte,
    @VariableByteInteger val remainingLength: Int,
)
// Generates FrameHeaderCodec with buffer.readVariableByteInteger() calls
```

### Context Fields — Dependent Parsing

Some custom fields need values from previously decoded fields. Use `contextFields` to pass them as extra arguments to your read/write functions:

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

### SPI Restrictions

- Cannot override built-in annotations (`@LengthPrefixed`, `@WireBytes`, etc.)
- Custom fields break batch optimization (each custom field is read/written individually)
- Two providers cannot register the same annotation FQN

---

## Manual Codecs

When you need full control — custom encoding logic, protocol-level optimizations, or formats that don't map to annotations — implement `Codec<T>` directly.

### The Codec Interface

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

Generated and manual codecs implement the same interface — they compose freely.

### Simple Example

```kotlin
data class SensorReading(val sensorId: UShort, val temperature: Int)

object SensorReadingCodec : Codec<SensorReading> {
    override fun decode(buffer: ReadBuffer) =
        SensorReading(buffer.readUnsignedShort(), buffer.readInt())

    override fun encode(buffer: WriteBuffer, value: SensorReading) {
        buffer.writeUShort(value.sensorId)
        buffer.writeInt(value.temperature)
    }

    override fun sizeOf(value: SensorReading) = 6
}
```

### Length-Prefixed Strings

Use `readLengthPrefixedUtf8String()` and `writeLengthPrefixedUtf8String()` for strings with a 2-byte big-endian length prefix:

```kotlin
data class ConnectMessage(val clientId: String, val username: String)

object ConnectMessageCodec : Codec<ConnectMessage> {
    override fun decode(buffer: ReadBuffer) = ConnectMessage(
        clientId = buffer.readLengthPrefixedUtf8String().second,
        username = buffer.readLengthPrefixedUtf8String().second,
    )

    override fun encode(buffer: WriteBuffer, value: ConnectMessage) {
        buffer.writeLengthPrefixedUtf8String(value.clientId)
        buffer.writeLengthPrefixedUtf8String(value.username)
    }
}
```

### Variable-Length Integers

`readVariableByteInteger()` and `writeVariableByteInteger()` handle variable-byte integers (1-4 bytes, max value 268,435,455):

```kotlin
data class PublishHeader(val topicLength: Int, val payloadLength: Int)

object PublishHeaderCodec : Codec<PublishHeader> {
    override fun decode(buffer: ReadBuffer) = PublishHeader(
        topicLength = buffer.readVariableByteInteger(),
        payloadLength = buffer.readVariableByteInteger(),
    )

    override fun encode(buffer: WriteBuffer, value: PublishHeader) {
        buffer.writeVariableByteInteger(value.topicLength)
        buffer.writeVariableByteInteger(value.payloadLength)
    }
}
```

### Composing Codecs

Use one codec inside another for nested structures:

```kotlin
data class Envelope(val version: UByte, val sensor: SensorReading)

object EnvelopeCodec : Codec<Envelope> {
    override fun decode(buffer: ReadBuffer) = Envelope(
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

### Manual Sealed Interface Dispatch

```kotlin
sealed interface Message { val typeCode: Byte }

data class PingMessage(val timestamp: Long) : Message {
    override val typeCode: Byte = 0x01
}
data class DataMessage(val payload: String) : Message {
    override val typeCode: Byte = 0x02
}

object MessageCodec : Codec<Message> {
    override fun decode(buffer: ReadBuffer): Message =
        when (val type = buffer.readByte()) {
            0x01.toByte() -> PingMessage(buffer.readLong())
            0x02.toByte() -> DataMessage(buffer.readLengthPrefixedUtf8String().second)
            else -> throw IllegalArgumentException("Unknown type: $type")
        }

    override fun encode(buffer: WriteBuffer, value: Message) {
        buffer.writeByte(value.typeCode)
        when (value) {
            is PingMessage -> buffer.writeLong(value.timestamp)
            is DataMessage -> buffer.writeLengthPrefixedUtf8String(value.payload)
        }
    }
}
```

Compare this to the `@PacketType` approach above — the generated version eliminates the manual dispatch boilerplate and catches duplicate type codes at compile time.

---

## PayloadReader

When your protocol has a length-delimited payload section to hand off to application code:

```kotlin
val payloadReader = ReadBufferPayloadReader(payloadBuffer)
val id = payloadReader.readInt()
val name = payloadReader.readString(nameLength)

// Or copy/transfer
val copy = payloadReader.copyToBuffer(BufferFactory.managed())
payloadReader.transferTo(writeBuffer)
```

`PayloadReader` tracks release state — use-after-release throws `IllegalStateException`.

## Streaming Integration

For streaming scenarios where data arrives in chunks, use `StreamProcessor` to accumulate bytes, then decode with any codec:

```kotlin
withPool { pool ->
    val processor = StreamProcessor.create(pool)

    for (chunk in networkChannel) {
        processor.append(chunk)

        while (processor.available() >= HEADER_SIZE) {
            val headerBuffer = processor.readBuffer(HEADER_SIZE)
            val message = MessageCodec.decode(headerBuffer)
            handleMessage(message)
        }
    }
}
```

## Real-World Example: Zero-Copy RIFF Image Decoder

This example decodes a custom RIFF-like image container and renders the bitmap in Jetpack Compose — without copying pixel data during parsing.

### Wire Format

```
┌────────────────────────────┐
│ FileHeader    (12 bytes)   │  magic("RIFF") + fileSize + format("IMG ")
├────────────────────────────┤
│ ChunkHeader   (8 bytes)    │  chunkId("meta") + chunkSize
│ ImageMetadata (variable)   │  width, height, depth, title
├────────────────────────────┤
│ ChunkHeader   (8 bytes)    │  chunkId("pxls") + chunkSize
│ Raw pixel data             │  ← zero-copy slice, never copied during parse
└────────────────────────────┘
```

### Define the Codec Types

Use `@ProtocolMessage` for all structured headers. The binary pixel payload is **not** a codec field — it's extracted manually with `readBytes()` for zero-copy.

```kotlin
@ProtocolMessage
data class FileHeader(
    val magic: Int,       // "RIFF" = 0x52494646
    val fileSize: UInt,   // total size after this field
    val format: Int,      // "IMG " = 0x494D4720
)

@ProtocolMessage
data class ChunkHeader(
    val chunkId: Int,     // 4-byte ASCII chunk ID
    val chunkSize: UInt,  // byte count of chunk data
)

@ProtocolMessage
data class ImageMetadata(
    val width: UInt,
    val height: UInt,
    val colorDepth: UShort,              // bits per pixel (32 = ARGB_8888)
    @LengthPrefixed val title: String,
)
```

:::tip Why not put pixel data in the codec?
`@ProtocolMessage` fields must be primitives, strings, or codec-composed types — not raw byte blobs. For binary payloads:

- **Hybrid (shown here)**: codec for headers, manual `readBytes()` for the blob — simplest approach
- **Custom SPI annotation**: create a `@BinarySlice("sizeField")` annotation with a `CodecFieldProvider` that generates `readBytes()` calls — fully codec-managed, reusable across protocols (see [Custom Annotations (SPI)](#custom-annotations-spi))
- **`@Payload` type parameter**: for payloads that have their own structure and codec, not for raw bytes
:::

### Decode with Zero-Copy Pixel Extraction

```kotlin
data class RiffImage(
    val metadata: ImageMetadata,
    val pixelData: ReadBuffer,  // zero-copy slice of the original buffer
)

object RiffImageDecoder {
    private const val MAGIC_RIFF = 0x52494646  // "RIFF"
    private const val FORMAT_IMG = 0x494D4720  // "IMG "
    private const val CHUNK_META = 0x6D657461  // "meta"
    private const val CHUNK_PXLS = 0x70786C73  // "pxls"

    fun decode(buffer: ReadBuffer): RiffImage {
        // Generated codecs parse the structured headers — type-safe, batch-optimized
        val header = FileHeaderCodec.decode(buffer)
        require(header.magic == MAGIC_RIFF && header.format == FORMAT_IMG)

        val metaChunk = ChunkHeaderCodec.decode(buffer)
        require(metaChunk.chunkId == CHUNK_META)
        val metadata = ImageMetadataCodec.decode(buffer)

        val pxlsChunk = ChunkHeaderCodec.decode(buffer)
        require(pxlsChunk.chunkId == CHUNK_PXLS)

        // Zero-copy: readBytes() returns a slice backed by the same memory
        val pixelData = buffer.readBytes(pxlsChunk.chunkSize.toInt())

        return RiffImage(metadata, pixelData)
    }
}
```

**What's zero-copy here:** `readBytes()` returns a `ReadBuffer` slice that shares the same underlying native memory as the original buffer. No pixel data is copied during parsing. The headers are tiny (28 bytes total) and parsed by the generated codec — only the multi-megabyte pixel payload gets the zero-copy treatment.

### Render in Compose Multiplatform (Skia)

Use `nativeAddress` to create a Skia `Pixmap` that wraps the buffer's memory directly — no copy:

```kotlin
@Composable
fun RiffBitmap(buffer: ReadBuffer, modifier: Modifier = Modifier) {
    // Hold a reference to the decoded image so the backing buffer stays alive
    val (imageBitmap, imageRef) = remember(buffer) {
        val img = RiffImageDecoder.decode(buffer)
        val w = img.metadata.width.toInt()
        val h = img.metadata.height.toInt()

        val nma = img.pixelData.nativeMemoryAccess
            ?: error("Use BufferFactory.Default for zero-copy Skia rendering")

        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        // Zero-copy: Pixmap wraps the buffer's native memory directly
        val pixmap = Pixmap(info, nma.nativeAddress, info.minRowBytes)
        Image.makeFromPixmap(pixmap).toComposeImageBitmap() to img
    }
    Image(bitmap = imageBitmap, contentDescription = null, modifier = modifier)
}
```

:::warning Buffer Lifetime
`Pixmap` does not own the memory — it borrows the buffer's native address. The original buffer (or pool) must stay alive while the image is in use. In the example above, `imageRef` keeps the `RiffImage` (and its backing `ReadBuffer` slice) alive alongside the `ImageBitmap` inside `remember`.
:::

### Android: Decompress Directly into HardwareBuffer (API 26+)

For the fastest path to the GPU, lock a `HardwareBuffer`, wrap the locked pointer with `BufferFactory.wrapNativeAddress`, and decompress directly into GPU-accessible memory — zero intermediate buffers:

```kotlin
@Composable
fun RiffBitmap(buffer: ReadBuffer, modifier: Modifier = Modifier) {
    val imageBitmap = remember(buffer) {
        val img = RiffImageDecoder.decode(buffer)
        val w = img.metadata.width.toInt()
        val h = img.metadata.height.toInt()
        val pixelSize = w * h * 4

        val hwBuffer = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN,
        )

        // Lock HardwareBuffer → get GPU-mapped memory address (JNI)
        val gpuAddr = NativeRenderer.lockHardwareBuffer(hwBuffer)

        // Wrap the GPU memory as a PlatformBuffer — zero-copy, non-owning
        val dst = BufferFactory.wrapNativeAddress(gpuAddr, pixelSize)

        // Decompress directly from source buffer into GPU memory
        StreamingDecompressor.create(CompressionAlgorithm.Raw).use(
            onOutput = { chunk -> dst.write(chunk) }
        ) { decompress -> decompress(img.pixelData) }

        // Unlock → pixels are in GPU memory, ready to render
        NativeRenderer.unlockHardwareBuffer(hwBuffer)

        Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
            .asImageBitmap()
    }
    Image(bitmap = imageBitmap, contentDescription = null, modifier = modifier)
}
```

The JNI helper is just lock/unlock — two small functions:

```c
#include <android/hardware_buffer_jni.h>

JNIEXPORT jlong JNICALL
Java_com_example_NativeRenderer_lockHardwareBuffer(JNIEnv *env, jclass clazz, jobject hw_buffer) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hw_buffer);
    void *dst = NULL;
    AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &dst);
    return (jlong)dst;
}

JNIEXPORT void JNICALL
Java_com_example_NativeRenderer_unlockHardwareBuffer(JNIEnv *env, jclass clazz, jobject hw_buffer) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hw_buffer);
    AHardwareBuffer_unlock(ahb, NULL);
}
```

### Writing the Container

```kotlin
fun encodeRiffImage(metadata: ImageMetadata, pixels: ReadBuffer): PlatformBuffer {
    val metadataSize = ImageMetadataCodec.sizeOf(metadata)!!
    val pixelSize = pixels.remaining()
    val totalSize = 4 + 8 + metadataSize + 8 + pixelSize  // format + 2 chunk headers + data

    val buffer = BufferFactory.Default.allocate(12 + totalSize)

    // File header
    FileHeaderCodec.encode(buffer, FileHeader(
        magic = 0x52494646,
        fileSize = totalSize.toUInt(),
        format = 0x494D4720,
    ))

    // Metadata chunk
    ChunkHeaderCodec.encode(buffer, ChunkHeader(0x6D657461, metadataSize.toUInt()))
    ImageMetadataCodec.encode(buffer, metadata)

    // Pixel data chunk — bulk copy from source buffer
    ChunkHeaderCodec.encode(buffer, ChunkHeader(0x70786C73, pixelSize.toUInt()))
    buffer.write(pixels)

    buffer.resetForRead()
    return buffer
}
```

### Copy Budget

| Step | Skia (`Pixmap`) | Android (`HardwareBuffer` + `wrapNativeAddress`) | Naive (`ByteArray`) |
|------|-----------------|--------------------------------------------------|---------------------|
| Parse headers (codec) | Zero-copy | Zero-copy | Zero-copy |
| Extract compressed data (`readBytes`) | Zero-copy (slice) | Zero-copy (slice) | 1 copy (`readByteArray`) |
| Decompress | → Direct buffer | → GPU memory (`wrapNativeAddress`) | → `ByteArray` |
| Render | Zero-copy (`Pixmap` borrows memory) | Zero-copy (GPU reads `HardwareBuffer`) | 1 copy (`wrap` + `copyPixels`) |
| **Intermediate copies** | **0** | **0** | **2** |

Both the Skia and HardwareBuffer paths achieve **zero intermediate copies** of pixel data. Decompressed pixels exist in exactly one place — either the Skia-wrapped Direct buffer or the GPU-mapped HardwareBuffer memory. `BufferFactory.wrapNativeAddress` is what makes the HardwareBuffer path possible from pure Kotlin.

---

## Extension Functions Reference

| Function | Description |
|----------|-------------|
| `codec.encodeToBuffer(value)` | Allocate a new buffer, encode, and return it ready for reading |
| `codec.testRoundTrip(value)` | Encode then decode, returning the decoded value |
| `codec.testRoundTrip(value, expectedBytes)` | Same, but also verifies the wire bytes match |
