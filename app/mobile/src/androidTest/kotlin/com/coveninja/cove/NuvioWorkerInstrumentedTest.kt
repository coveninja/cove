package com.coveninja.cove

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coveninja.cove.backend.nuvio.AndroidNuvioWorkerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NuvioWorkerInstrumentedTest {
    @Test
    fun testQuickJsWorkerRunsInIsolatedProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var worker: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                worker = service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = connected.countDown()
        }
        assertTrue(
            context.bindService(
                Intent(context, AndroidNuvioWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue("worker did not connect", connected.await(5, TimeUnit.SECONDS))
            val input = ParcelFileDescriptor.createPipe()
            val output = ParcelFileDescriptor.createPipe()
            Thread {
                ParcelFileDescriptor.AutoCloseOutputStream(input[1]).bufferedWriter().use {
                    it.write(INVOCATION)
                }
            }.start()
            val brokerCalled = AtomicBoolean(false)
            val broker = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != FETCH_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(FETCH_DESCRIPTOR)
                    val url = requireNotNull(data.readString())
                    data.readString() // serialized fetch options
                    val output = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    require(url == "https://example.test/streams")
                    brokerCalled.set(true)
                    ParcelFileDescriptor.AutoCloseOutputStream(output).bufferedWriter().use {
                        it.write(FETCH_RESULT)
                    }
                    reply?.writeNoException()
                    return true
                }
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(WORKER_DESCRIPTOR)
                input[0].writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                output[1].writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                data.writeStrongBinder(broker)
                assertTrue(worker!!.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
                input[0].close()
                output[1].close()
            }
            val result = ParcelFileDescriptor.AutoCloseInputStream(output[0]).bufferedReader().use { it.readText() }
            assertTrue("isolated worker did not use the privileged fetch broker", brokerCalled.get())
            assertTrue(result, result.contains("https://video.example/movie"))
            assertTrue(result, result.contains("\"error\":\"\""))
        } finally {
            context.unbindService(connection)
        }
    }

    private companion object {
        const val WORKER_DESCRIPTOR = "com.coveninja.cove.nuvio.worker"
        const val FETCH_DESCRIPTOR = "com.coveninja.cove.nuvio.fetch"
        const val FETCH_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1
        const val FETCH_RESULT =
            "{\"response\":{\"status\":200,\"headers\":{},\"body\":\"{\\\"name\\\":\\\"1080p\\\",\\\"url\\\":\\\"https://video.example/movie\\\"}\"},\"error\":\"\"}"
        val INVOCATION = """
            {
              "scraperId":"smoke",
              "code":"module.exports.getStreams = async () => { const response = await fetch('https://example.test/streams'); const stream = await response.json(); return [stream]; };",
              "tmdbId":1,
              "mediaType":"movie",
              "title":"Smoke",
              "year":2026,
              "imdbId":"tt0000001"
            }
        """.trimIndent()
    }
}
