package com.tvboxremote.app

import com.tvboxremote.app.receiver.ReceiverShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReceiverShareTest {
    @Test
    fun servesTheApkAtAnyPath() {
        val apk = File.createTempFile("receiver", ".apk")
        val bytes = ByteArray(20_000) { (it % 251).toByte() }.also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }
        apk.writeBytes(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val downloaded = CountDownLatch(1)
        val server = ReceiverShare.serve(scope, apk) { downloaded.countDown() }
        assertNotNull(server)
        try {
            // The box types only "address:port", so the root path gets the file.
            for (path in listOf("/", "/receiver.apk")) {
                val c = URL("http://127.0.0.1:${server!!.port}$path").openConnection() as HttpURLConnection
                assertEquals(200, c.responseCode)
                assertEquals("application/vnd.android.package-archive", c.contentType)
                assertEquals("attachment; filename=\"receiver.apk\"", c.getHeaderField("Content-Disposition"))
                assertEquals(bytes.size.toLong(), c.contentLengthLong)
                assertArrayEquals(bytes, c.inputStream.use { it.readBytes() })
                c.disconnect()
            }
            assert(downloaded.await(5, TimeUnit.SECONDS))

            val favicon = URL("http://127.0.0.1:${server!!.port}/favicon.ico").openConnection() as HttpURLConnection
            assertEquals(404, favicon.responseCode)
            favicon.disconnect()
        } finally {
            server?.stop()
            scope.cancel()
            apk.delete()
        }
    }
}
