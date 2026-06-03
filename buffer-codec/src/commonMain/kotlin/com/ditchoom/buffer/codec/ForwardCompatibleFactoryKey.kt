package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory

/**
 * Caller-controlled allocator for the opaque bytes a `@ForwardCompatible`
 * decoder preserves when it skips an unknown sealed variant.
 *
 * Inject via [DecodeContext.with]; the generated forward-compatible decode
 * reads `context[ForwardCompatibleFactoryKey] ?: BufferFactory.managed()`.
 * The default is `managed()` — GC-lifetime heap bytes that need no manual
 * free. A caller relaying or persisting many frames can supply a pool-backed
 * or native [BufferFactory] for the preserved payloads and take ownership of
 * freeing them.
 *
 * ```kotlin
 * val ctx = DecodeContext.Empty.with(ForwardCompatibleFactoryKey, myPool.asFactory())
 * val op = OpCodec.decode(buffer, ctx)
 * ```
 *
 * @see com.ditchoom.buffer.codec.annotations.ForwardCompatible
 */
public object ForwardCompatibleFactoryKey : DecodeKey<BufferFactory>
