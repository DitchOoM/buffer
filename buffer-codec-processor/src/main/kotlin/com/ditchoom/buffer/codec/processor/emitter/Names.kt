package com.ditchoom.buffer.codec.processor.emitter

import com.squareup.kotlinpoet.ClassName

/**
 * KotlinPoet `ClassName` literals for the buffer-codec runtime types the emitter
 * references. Centralising them keeps the per-shape emitters declarative.
 */
internal object Names {
    val ReadBuffer = ClassName("com.ditchoom.buffer", "ReadBuffer")
    val WriteBuffer = ClassName("com.ditchoom.buffer", "WriteBuffer")
    val Codec = ClassName("com.ditchoom.buffer.codec", "Codec")
    val CodecContext = ClassName("com.ditchoom.buffer.codec", "CodecContext")
    val DecodeContext = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
    val EncodeContext = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
    val PeekResult = ClassName("com.ditchoom.buffer.stream", "PeekResult")
    val PeekResultSize = ClassName("com.ditchoom.buffer.stream", "PeekResult", "Size")
    val PeekResultNeedsMore = ClassName("com.ditchoom.buffer.stream", "PeekResult", "NeedsMoreData")
    val StreamProcessor = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
    val SuspendingStreamProcessor = ClassName("com.ditchoom.buffer.stream", "SuspendingStreamProcessor")
    val IllegalArgumentException = ClassName("kotlin", "IllegalArgumentException")
}
