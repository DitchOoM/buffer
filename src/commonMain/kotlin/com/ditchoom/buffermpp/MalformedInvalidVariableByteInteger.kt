@file:Suppress("EXPERIMENTAL_UNSIGNED_LITERALS", "EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffermpp

class MalformedInvalidVariableByteInteger(value: UInt) : Exception(
    "Malformed Variable Byte Integer: This " +
            "property must be a number between 0 and %VARIABLE_BYTE_INT_MAX . Read value was: $value"
)