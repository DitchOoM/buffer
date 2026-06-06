package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/*
 * Shared compile-time constants for [CodecEmitter] — qualified names, KotlinPoet
 * [ClassName]/[MemberName] handles, and the scalar-kind lookup tables. Extracted
 * verbatim from `CodecEmitter`'s companion object as the first step of the
 * incremental file split; these are package-`internal` so the unqualified
 * references throughout `CodecEmitter` resolve unchanged (byte-identical codegen
 * verified by the snapshot suite).
 */

internal const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
internal const val PAYLOAD_QNAME = "com.ditchoom.buffer.codec.Payload"
internal const val PAYLOAD_PKG = "com.ditchoom.buffer.codec"
internal const val PAYLOAD_SIMPLE = "Payload"
internal const val OWNED_BYTES_HANDLE_QNAME = "com.ditchoom.buffer.codec.OwnedBytesHandle"
internal const val BOUNDING_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.BoundingLengthCodec"
internal const val VARIABLE_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.VariableLengthCodec"
internal const val FRAMED_BY_QNAME = "com.ditchoom.buffer.codec.annotations.FramedBy"
internal const val FRAME_DETECTOR_QNAME = "com.ditchoom.buffer.codec.FrameDetector"

// Batching gate. A coalesced read targets exactly 2, 4, or 8 bytes —
// the natural-width reads on `ReadBuffer`. 3/5/6/7 prefixes are
// never emitted; the coalescer keeps them as individual reads.
internal val BATCH_ALIGNMENTS = setOf(2, 4, 8)

internal val BYTE_ORDER_CN = ClassName("com.ditchoom.buffer", "ByteOrder")
internal val SWAP_BYTES_MN = MemberName("com.ditchoom.buffer", "swapBytes")

internal val SUPPORTED_SCALARS =
    mapOf(
        "kotlin.Boolean" to ScalarKind.Boolean,
        "kotlin.UByte" to ScalarKind.UByte,
        "kotlin.UShort" to ScalarKind.UShort,
        "kotlin.UInt" to ScalarKind.UInt,
        "kotlin.ULong" to ScalarKind.ULong,
        "kotlin.Byte" to ScalarKind.Byte,
        "kotlin.Short" to ScalarKind.Short,
        "kotlin.Int" to ScalarKind.Int,
        "kotlin.Long" to ScalarKind.Long,
        "kotlin.Float" to ScalarKind.Float,
        "kotlin.Double" to ScalarKind.Double,
    )

// Slice — qnames accepted as `@DispatchValue`
// property return types, mapped to the kind that drives the
// dispatch-site Int coercion. Long / ULong are excluded — the
// `@PacketType.value` annotation parameter is `Int` and can't
// address values beyond `Int.MAX_VALUE`. Mirror of the
// ProtocolMessageProcessor `DISPATCH_VALUE_RETURN_RANGES`
// validator-side set.
internal val DISPATCH_VALUE_RETURN_KINDS =
    mapOf(
        "kotlin.Boolean" to ScalarKind.Boolean,
        "kotlin.Byte" to ScalarKind.Byte,
        "kotlin.UByte" to ScalarKind.UByte,
        "kotlin.Short" to ScalarKind.Short,
        "kotlin.UShort" to ScalarKind.UShort,
        "kotlin.Int" to ScalarKind.Int,
        "kotlin.UInt" to ScalarKind.UInt,
    )

internal val READ_BUFFER_CN = ClassName("com.ditchoom.buffer", "ReadBuffer")
internal val WRITE_BUFFER_CN = ClassName("com.ditchoom.buffer", "WriteBuffer")
internal val PLATFORM_BUFFER_CN = ClassName("com.ditchoom.buffer", "PlatformBuffer")
internal val BUFFER_FACTORY_CN = ClassName("com.ditchoom.buffer", "BufferFactory")
internal val BUFFER_FACTORY_DEFAULT_MN =
    MemberName("com.ditchoom.buffer", "Default")
internal val BUFFER_USE_MN =
    MemberName("com.ditchoom.buffer", "use")
internal val CODEC_CN = ClassName("com.ditchoom.buffer.codec", "Codec")
internal val DECODER_CN = ClassName("com.ditchoom.buffer.codec", "Decoder")
internal val DECODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
internal val ENCODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
internal val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
internal val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
internal val STREAM_PROCESSOR_CN = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
internal val DECODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "DecodeException")
internal val ENCODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "EncodeException")
internal val FRAMED_ENCODER_CN = ClassName("com.ditchoom.buffer.codec", "FramedEncoder")
internal val FORWARD_COMPATIBLE_FACTORY_KEY_CN =
    ClassName("com.ditchoom.buffer.codec", "ForwardCompatibleFactoryKey")
internal val BUFFER_FACTORY_MANAGED_MN =
    MemberName("com.ditchoom.buffer", "managed")

// Accepted types for the `@UnknownVariant` `raw` parameter — the
// opaque preserved payload. `factory.allocate(...)` yields a
// `PlatformBuffer` (assignable to a `ReadBuffer`-typed field too),
// so both are valid declared types.
internal val FORWARD_COMPATIBLE_RAW_QNAMES =
    setOf("com.ditchoom.buffer.PlatformBuffer", "com.ditchoom.buffer.ReadBuffer")
internal val CHARSET_CN = ClassName("com.ditchoom.buffer", "Charset")
internal val STRING_NULLABLE_TN = ClassName("kotlin", "String").copy(nullable = true)
