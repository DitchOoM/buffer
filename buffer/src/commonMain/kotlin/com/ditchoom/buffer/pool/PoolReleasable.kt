package com.ditchoom.buffer.pool

/**
 * Marker interface for objects that hold a reference to a pooled buffer.
 * Calling [releaseToPool] decrements the parent's reference count.
 */
interface PoolReleasable {
    fun releaseToPool()
}
