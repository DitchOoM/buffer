package com.ditchoom.buffer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@MediumTest
class IPCTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule.withTimeout(3, TimeUnit.SECONDS)

    @Test
    fun sharedMemoryBuffer() =
        runBlocking {
            val mutex = Mutex(true)
            lateinit var stub: BufferIpcTestAidl
            serviceRule.bindService(
                Intent(InstrumentationRegistry.getInstrumentation().context, IPCSimpleService::class.java),
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        stub = BufferIpcTestAidl.Stub.asInterface(service)
                        mutex.unlock()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        throw IllegalStateException("Service Disconnected")
                    }
                },
                Context.BIND_AUTO_CREATE,
            )
            mutex.lock()
            val num = Random.nextInt()
            val buffer2 = stub.aidl(num, 2)
            assertTrue { buffer2.byteBuffer.isDirect }
            buffer2.resetForRead()
            assertEquals(num, buffer2.readInt())
        }

    @Test
    fun copyJvmDirect() =
        runBlocking {
            val mutex = Mutex(true)
            lateinit var stub: BufferIpcTestAidl
            serviceRule.bindService(
                Intent(InstrumentationRegistry.getInstrumentation().context, IPCSimpleService::class.java),
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        stub = BufferIpcTestAidl.Stub.asInterface(service)
                        mutex.unlock()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        throw IllegalStateException("Service Disconnected")
                    }
                },
                Context.BIND_AUTO_CREATE,
            )
            mutex.lock()
            val num = Random.nextInt()
            val buffer1 = stub.aidl(num, 1)
            assertTrue { buffer1.byteBuffer.isDirect }
            buffer1.resetForRead()
            assertEquals(num, buffer1.readInt())
        }

    @Test
    fun copyJvmHeap() =
        runBlocking {
            val mutex = Mutex(true)
            lateinit var stub: BufferIpcTestAidl
            serviceRule.bindService(
                Intent(InstrumentationRegistry.getInstrumentation().context, IPCSimpleService::class.java),
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        stub = BufferIpcTestAidl.Stub.asInterface(service)
                        mutex.unlock()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        throw IllegalStateException("Service Disconnected")
                    }
                },
                Context.BIND_AUTO_CREATE,
            )
            mutex.lock()
            val num = Random.nextInt()
            val buffer0 = stub.aidl(num, 0)
            assertFalse { buffer0.byteBuffer.isDirect }
            buffer0.resetForRead()
            assertEquals(num, buffer0.readInt())
        }
}
