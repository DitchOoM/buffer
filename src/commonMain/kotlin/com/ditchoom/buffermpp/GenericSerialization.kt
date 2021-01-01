@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS")

package com.ditchoom.buffermpp

import kotlin.reflect.KClass

object GenericSerialization {
    val serializerMap = SketchyBufferSerializerMap().also {
        it[CharSequence::class] = CharSequenceSerializer
        it[String::class] = CharSequenceSerializer
    }
    val deserializerMap = SketchyBufferDeserializerMap().also {
        it[CharSequence::class] = CharSequenceSerializer
        it[String::class] = CharSequenceSerializer
    }

    fun <T : Any> registerSerializer(serializer: BufferSerializer<T>) {
        serializerMap[serializer.kClass] = serializer
    }

    fun <T : Any> serialize(buffer: WriteBuffer, genericType: GenericType<T>) {
        if (genericType.kClass == Unit::class) {
            return
        }
        serializerMap.get<T>(genericType.kClass).serialize(buffer, genericType.obj)
    }

    fun <T : Any> size(obj: T, type: KClass<T>): UInt {
        if (type == Unit::class) {
            return 0u
        }
        return serializerMap.get<T>(type).size(obj)
    }

    inline fun <reified T : Any> registerDeserializer(deserializer: BufferDeserializer<T>) {
        deserializerMap[T::class] = deserializer
    }

    fun deserialize(deserializationParameters: DeserializationParameters): GenericType<*>? {
        if (deserializationParameters.length == 0u) {
            return null
        }
        return CharSequenceSerializer.deserialize(deserializationParameters)
    }
}

object CharSequenceSerializer : BufferSerializer<CharSequence>, BufferDeserializer<CharSequence> {
    override val kClass: KClass<CharSequence> = CharSequence::class
    override fun size(obj: CharSequence) = obj.toString().utf8Length()

    override fun serialize(buffer: WriteBuffer, obj: CharSequence): Boolean {
        buffer.writeUtf8(obj)
        return true
    }

    override fun deserialize(params: DeserializationParameters): GenericType<CharSequence>? {
        if (params.length == 0u) {
            return null
        }
        val obj = params.buffer.readUtf8(params.length)
        return GenericType(obj, CharSequence::class)
    }
}

object StringSerializer : BufferSerializer<String>, BufferDeserializer<String> {
    override val kClass: KClass<String> = String::class
    override fun size(obj: String) = obj.utf8Length()

    override fun serialize(buffer: WriteBuffer, obj: String): Boolean {
        buffer.writeUtf8(obj)
        return true
    }

    override fun deserialize(params: DeserializationParameters): GenericType<String>? {
        if (params.length == 0u) {
            return null
        }
        val obj = params.buffer.readUtf8(params.length).toString()
        return GenericType(obj, String::class)
    }
}