@file:Suppress("ktlint:standard:filename")

package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp

data class CompileResult(
    val exitCode: KotlinCompilation.ExitCode,
    val messages: String,
)

/**
 * Annotation source included directly in test compilations so KSP can resolve
 * them from source rather than binary jars (avoids Kotlin version mismatch issues
 * with kctfork's embedded compiler).
 */
private val annotationSource =
    SourceFile.kotlin(
        "Annotations.kt",
        """
    package com.ditchoom.buffer.codec.annotations

    enum class Endianness { Default, Big, Little }

    enum class Direction { Infer, Codec, DecodeOnly, EncodeOnly }

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class ProtocolMessage(
        val wireOrder: Endianness = Endianness.Default,
        val direction: Direction = Direction.Infer,
    )

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class PacketType(val value: Int, val wire: Int = -1)

    @Target(AnnotationTarget.TYPE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class Payload

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class DispatchOn(val type: kotlin.reflect.KClass<*>)

    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.BINARY)
    annotation class DispatchValue

    enum class LengthPrefix {
        Byte, Short, Int,
    }

    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class LengthPrefixed(val prefix: LengthPrefix = LengthPrefix.Short)

    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class RemainingBytes

    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class LengthFrom(val field: String)

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class WireBytes(val value: Int)

    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class WhenTrue(val expression: String)

    // Endianness enum already defined above with ProtocolMessage

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class WireOrder(val order: Endianness)

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class UseCodec(val codec: kotlin.reflect.KClass<*>)

    """,
    )

/**
 * Stub ReadBuffer and WriteBuffer interfaces for tests that need to reference buffer types.
 */
private val bufferStubs =
    SourceFile.kotlin(
        "BufferStubs.kt",
        """
    package com.ditchoom.buffer
    interface ReadBuffer {
        fun readByte(): Byte
        fun readUnsignedByte(): UByte
        fun readShort(): Short
        fun readUnsignedShort(): UShort
        fun readInt(): Int
        fun readUnsignedInt(): UInt
        fun readLong(): Long
        fun readUnsignedLong(): ULong
        fun readFloat(): Float
        fun readDouble(): Double
        fun readString(length: Int): String
        fun readBytes(size: Int): ReadBuffer
        fun remaining(): Int
        fun position(): Int
        fun position(newPosition: Int)
    }
    interface WriteBuffer {
        fun writeByte(value: Byte): WriteBuffer
        fun writeUByte(value: UByte): WriteBuffer
        fun writeShort(value: Short): WriteBuffer
        fun writeUShort(value: UShort): WriteBuffer
        fun writeInt(value: Int): WriteBuffer
        fun writeUInt(value: UInt): WriteBuffer
        fun writeLong(value: Long): WriteBuffer
        fun writeULong(value: ULong): WriteBuffer
        fun writeFloat(value: Float): WriteBuffer
        fun writeDouble(value: Double): WriteBuffer
        fun writeString(text: CharSequence): WriteBuffer
        fun position(): Int
        fun position(newPosition: Int)
    }
    enum class ByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }
    fun Short.reverseBytes(): Short = TODO()
    fun Int.reverseBytes(): Int = TODO()
    fun Long.reverseBytes(): Long = TODO()
    fun ReadBuffer.readLengthPrefixedUtf8String(): Pair<Int, String> = TODO()
    fun WriteBuffer.writeLengthPrefixedUtf8String(value: String): WriteBuffer = TODO()
    fun ReadBuffer.readVariableByteInteger(): Int = TODO()
    fun WriteBuffer.writeVariableByteInteger(value: Int): WriteBuffer = TODO()
    fun variableByteSizeInt(value: Int): Int = TODO()
    """,
    )

/**
 * StreamProcessor stubs so generated peekFrameSize code compiles in tests.
 */
private val streamStubs =
    SourceFile.kotlin(
        "StreamStubs.kt",
        """
    package com.ditchoom.buffer.stream
    import kotlin.jvm.JvmInline
    sealed interface PeekResult {
        @JvmInline value class Size(val bytes: Int) : PeekResult
        data object NeedsMoreData : PeekResult
    }
    interface StreamProcessor {
        fun available(): Int
        fun peekByte(offset: Int = 0): Byte
        fun peekShort(offset: Int = 0): Short
        fun peekInt(offset: Int = 0): Int
        fun peekLong(offset: Int = 0): Long
    }
    interface SuspendingStreamProcessor {
        fun available(): Int
        suspend fun peekByte(offset: Int = 0): Byte
        suspend fun peekShort(offset: Int = 0): Short
        suspend fun peekInt(offset: Int = 0): Int
        suspend fun peekLong(offset: Int = 0): Long
    }
    """,
    )

/**
 * Codec stub for tests that check generated code compilation.
 */
private val codecStubs =
    SourceFile.kotlin(
        "CodecStubs.kt",
        """
    package com.ditchoom.buffer.codec
    import com.ditchoom.buffer.ReadBuffer
    import com.ditchoom.buffer.WriteBuffer
    import com.ditchoom.buffer.stream.PeekResult
    import com.ditchoom.buffer.stream.StreamProcessor
    import kotlin.jvm.JvmInline
    interface CodecContext {
        operator fun <T : Any> get(key: Key<T>): T?
        abstract class Key<T : Any>
    }
    interface DecodeContext : CodecContext {
        fun <T : Any> with(key: CodecContext.Key<T>, value: T): DecodeContext
        companion object {
            val Empty: DecodeContext = object : DecodeContext {
                override fun <T : Any> get(key: CodecContext.Key<T>): T? = null
                override fun <T : Any> with(key: CodecContext.Key<T>, value: T): DecodeContext = this
            }
        }
    }
    interface EncodeContext : CodecContext {
        fun <T : Any> with(key: CodecContext.Key<T>, value: T): EncodeContext
        companion object {
            val Empty: EncodeContext = object : EncodeContext {
                override fun <T : Any> get(key: CodecContext.Key<T>): T? = null
                override fun <T : Any> with(key: CodecContext.Key<T>, value: T): EncodeContext = this
            }
        }
    }
    fun interface Decoder<out T> {
        fun decode(buffer: ReadBuffer): T
    }
    interface Encoder<in T> {
        fun encode(buffer: WriteBuffer, value: T)
        val wireSizeHint: Int get() = 16
    }
    interface Codec<T> : Encoder<T>, Decoder<T> {
        fun decode(buffer: ReadBuffer, context: DecodeContext): T
        fun encode(buffer: WriteBuffer, value: T, context: EncodeContext)
        override fun decode(buffer: ReadBuffer): T = decode(buffer, DecodeContext.Empty)
        override fun encode(buffer: WriteBuffer, value: T) = encode(buffer, value, EncodeContext.Empty)
        fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NeedsMoreData
    }
    """,
    )

private val payloadStubs =
    SourceFile.kotlin(
        "PayloadStubs.kt",
        """
    package com.ditchoom.buffer.codec.payload
    import com.ditchoom.buffer.ReadBuffer
    interface PayloadReader {
        fun readByte(): Byte
        fun readShort(): Short
        fun readInt(): Int
        fun readLong(): Long
        fun readFloat(): Float
        fun readDouble(): Double
        fun readString(length: Int): String
        fun remaining(): Int
    }
    class ReadBufferPayloadReader(private val buffer: ReadBuffer) : PayloadReader {
        override fun readByte(): Byte = TODO()
        override fun readShort(): Short = TODO()
        override fun readInt(): Int = TODO()
        override fun readLong(): Long = TODO()
        override fun readFloat(): Float = TODO()
        override fun readDouble(): Double = TODO()
        override fun readString(length: Int): String = TODO()
        override fun remaining(): Int = TODO()
        fun release() {}
    }
    """,
    )

fun compileWithKsp(vararg sources: SourceFile): CompileResult {
    val allSources = listOf(annotationSource, codecStubs, bufferStubs, streamStubs) + sources.toList()
    val compilation =
        KotlinCompilation().apply {
            this.sources = allSources
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ProtocolMessageProcessorProvider()
            }
            kotlincArguments = listOf("-Xskip-metadata-version-check")
        }
    val result = compilation.compile()
    return CompileResult(result.exitCode, result.messages)
}

fun compileWithKspAndBufferStubs(vararg sources: SourceFile): CompileResult {
    val allSources = listOf(annotationSource, codecStubs, bufferStubs, streamStubs) + sources.toList()
    val compilation =
        KotlinCompilation().apply {
            this.sources = allSources
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ProtocolMessageProcessorProvider()
            }
            kotlincArguments = listOf("-Xskip-metadata-version-check")
        }
    val result = compilation.compile()
    return CompileResult(result.exitCode, result.messages)
}

fun compileWithKspAndPayloadStubs(vararg sources: SourceFile): CompileResult {
    val allSources = listOf(annotationSource, codecStubs, bufferStubs, streamStubs, payloadStubs) + sources.toList()
    val compilation =
        KotlinCompilation().apply {
            this.sources = allSources
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ProtocolMessageProcessorProvider()
            }
            kotlincArguments = listOf("-Xskip-metadata-version-check")
        }
    val result = compilation.compile()
    return CompileResult(result.exitCode, result.messages)
}

private val customFunctionStubs =
    SourceFile.kotlin(
        "CustomFunctionStubs.kt",
        """
    package com.ditchoom.buffer.codec.test
    import com.ditchoom.buffer.ReadBuffer
    import com.ditchoom.buffer.WriteBuffer

    fun ReadBuffer.readRepeatedShorts(count: UByte): List<Short> = TODO()
    fun WriteBuffer.writeRepeatedShorts(items: List<Short>, count: UByte): Unit = TODO()
    fun repeatedShortsSize(items: List<Short>): Int = TODO()

    fun ReadBuffer.readPropertyBag(): Map<Int, Int> = TODO()
    fun WriteBuffer.writePropertyBag(props: Map<Int, Int>): Unit = TODO()

    fun ReadBuffer.readFixedInt(): Int = TODO()
    fun WriteBuffer.writeFixedInt(value: Int): Unit = TODO()
    """,
    )

fun compileWithKspAndCustomProviders(
    vararg sources: SourceFile,
    providers: List<CodecFieldProvider> = emptyList(),
): CompileResult {
    val allSources =
        listOf(annotationSource, codecStubs, bufferStubs, streamStubs, customFunctionStubs) + sources.toList()
    val compilation =
        KotlinCompilation().apply {
            this.sources = allSources
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += ProtocolMessageProcessorProvider(providers)
            }
            kotlincArguments = listOf("-Xskip-metadata-version-check")
        }
    val result = compilation.compile()
    return CompileResult(result.exitCode, result.messages)
}
