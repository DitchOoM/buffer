package com.ditchoom.buffer.crypto

/** RFC 2104 HMAC inner-padding byte, repeated across the key block. */
internal const val HMAC_IPAD = 0x36

/** RFC 2104 HMAC outer-padding byte, repeated across the key block. */
internal const val HMAC_OPAD = 0x5c
