package com.ditchoom.buffer

import android.app.Service
import android.content.Intent
import android.os.IBinder

class IPCSimpleService : Service() {
    override fun onBind(intent: Intent?): IBinder =
        object : BufferIpcTestAidl.Stub() {
            private val byteSize = 4 * 1024 * 1024

            override fun aidl(
                num: Int,
                type: Int,
            ): JvmBuffer {
                val factory: BufferFactory =
                    when (type) {
                        0 -> BufferFactory.managed()
                        1 -> BufferFactory.Default
                        else -> BufferFactory.shared()
                    }
                val buffer = factory.allocate(byteSize)
                buffer.writeInt(num)
                return buffer as JvmBuffer
            }
        }
}
