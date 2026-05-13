package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory

/**
 * Canonical [DecodeContext] / [EncodeContext] key for the consumer-supplied
 * [BufferFactory] codecs should use when allocating consumer-owned buffers.
 *
 * Read by codecs implementing the buffer-codec lockdown's **Pattern #2**
 * (consumer-owned [com.ditchoom.buffer.PlatformBuffer] — see
 * [OwnedBytesHandleCodec]) and **Pattern #3** (consumer-owned ByteArray).
 * When the key is absent, the canonical fallback is [BufferFactory.Default]
 * (codecs may choose a different fallback when documented — e.g. test
 * fixtures fall back to a managed-heap factory to dodge Linux's leak-prone
 * default).
 */
object BufferFactoryKey : CodecKey<BufferFactory>
