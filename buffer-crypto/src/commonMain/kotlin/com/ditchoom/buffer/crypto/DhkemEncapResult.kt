package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/** Result of a DHKEM encapsulation: the [sharedSecret] (secure, caller frees) and the [enc] to send. */
internal class DhkemEncapResult(
    val sharedSecret: PlatformBuffer,
    val enc: ReadBuffer,
)
