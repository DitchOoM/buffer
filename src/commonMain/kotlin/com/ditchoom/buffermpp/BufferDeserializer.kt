@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS")

package com.ditchoom.buffermpp

interface BufferDeserializer<T : Any> {
    fun deserialize(params: DeserializationParameters): GenericType<T>?
}

data class DeserializationParameters(
    val buffer: ReadBuffer,
    val length: UInt = 0u,
    val path: CharSequence = "",
    val properties: Map<Int, Any> = emptyMap(),
    val headers: Map<CharSequence, Set<CharSequence>> = emptyMap()
)

fun HashMap<CharSequence, HashSet<CharSequence>>.add(k: CharSequence, v: CharSequence) {
    val set = get(k) ?: HashSet()
    set += v
    put(k, set)
}