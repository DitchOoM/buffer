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
                val zone =
                    when (type) {
                        0 -> AllocationZone.Heap
                        1 -> AllocationZone.Direct
                        else -> AllocationZone.SharedMemory
                    }
                val buffer = PlatformBuffer.allocate(byteSize, zone)
                buffer.writeInt(num)
                return buffer as JvmBuffer
            }
        }
}
