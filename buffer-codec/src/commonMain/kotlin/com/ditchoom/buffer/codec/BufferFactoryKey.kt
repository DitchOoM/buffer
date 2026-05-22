package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory

/**
 * Canonical [DecodeContext] / [EncodeContext] key for the consumer-supplied
 * [BufferFactory] codecs should use when allocating consumer-owned buffers.
 *
 * Read by codecs implementing the buffer-codec lockdown's **Pattern #2**
 * (consumer-owned [com.ditchoom.buffer.PlatformBuffer] — see
 * [OwnedBytesHandleCodec]) and **Pattern #3** (consumer-owned ByteArray).
 * When the key is absent, the canonical fallback is [BufferFactory.Default],
 * which is GC- / ARC- / cleaner-managed on every supported platform.
 */
object BufferFactoryKey : CodecKey<BufferFactory>
