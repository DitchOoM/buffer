package com.ditchoom.buffermpp

import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferPoolTests {

    //@Test
    fun recycles() {
        val bufferPool = BufferPool()
        var buffer: PlatformBuffer? = null
        bufferPool.borrow {
            buffer = it
        }
        assertTrue(bufferPool.pool.contains(buffer!!))
        bufferPool.borrow {
            assertEquals(buffer!!, it)
        }
    }

    //@Test
    fun recyclesAsync() {
        val bufferPool = BufferPool()
        var buffer: PlatformBuffer? = null
        bufferPool.borrowAsync { borrowedBuffer, cb ->
            buffer = borrowedBuffer
            cb.recycle()
        }
        assertTrue(bufferPool.pool.contains(buffer!!))
        bufferPool.borrow {
            assertEquals(buffer!!, it)
        }
    }

    //@Test
    fun recyclesSuspend() = runTestBlocking {
        val bufferPool = BufferPool()
        var buffer: PlatformBuffer? = null
        bufferPool.borrowSuspend {
            buffer = it
        }
        assertTrue(bufferPool.pool.contains(buffer!!))
        bufferPool.borrowSuspend {
            assertEquals(buffer!!, it)
        }
    }


    //@Test
    fun clearsPool() {
        val bufferPool = BufferPool()
        assertTrue(bufferPool.pool.isEmpty())
        bufferPool.borrowAsync { _, cb ->
            cb.recycle()
        }
        assertEquals(1, bufferPool.pool.size)
        bufferPool.borrow {}
        assertEquals(1, bufferPool.pool.size)
        bufferPool.releaseAllBuffers()
        assertTrue(bufferPool.pool.isEmpty())
    }
}
