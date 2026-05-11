package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * Native actual: `BufferFactory.Default` on Apple targets (NSData /
 * NSMutableData are ARC-cleaned), `BufferFactory.managed()` on Linux
 * (Default there is `NativeBuffer` — malloc/free which leaks without
 * explicit `close()`). The fixture holds buffers for the lifetime of the
 * typed handle with no close story, so on Linux specifically we use the
 * heap-backed managed factory.
 */
@OptIn(ExperimentalNativeApi::class)
internal actual val testFixtureFactory: BufferFactory =
    if (Platform.osFamily == OsFamily.LINUX) BufferFactory.managed() else BufferFactory.Default
