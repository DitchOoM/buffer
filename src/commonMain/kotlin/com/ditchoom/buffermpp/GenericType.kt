package com.ditchoom.buffermpp

import kotlin.reflect.KClass

class GenericType<T : Any>(val obj: T, val kClass: KClass<T>) {
    override fun equals(other: Any?): Boolean {
        return if (obj is CharSequence && other is GenericType<*> && other.obj is CharSequence) {
            obj.toString() == other.toString()
        } else if (other is GenericType<*>) {
            other.obj == obj
        } else false
    }

    override fun hashCode() = obj.hashCode()

    override fun toString() = obj.toString()
}