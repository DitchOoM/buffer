package com.ditchoom.buffer

import java.nio.ByteBuffer

// ktlint (no .editorconfig) collapses this expression body onto one line, so it cannot be wrapped.
@Suppress("MaxLineLength")
internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long = AndroidBufferHelper.getDirectBufferAddress(buffer)
