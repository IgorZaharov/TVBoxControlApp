package com.tvboxremote.app.receiver

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * The phone gives the receiver to the box over the local Wi-Fi: it gets receiver.apk from GitHub, then serves it on a
 * short address such as 192.168.1.23:8080. On the box, the user types that address into Downloader or a browser.
 * The short address is much easier to type with a TV remote than the GitHub link.
 */
object ReceiverShare {
    /** The ports to try, in order. 8080 is the usual one; the others are for a phone where 8080 is in use. */
    private val PORTS = listOf(8080, 8000, 8888) + (8081..8099)

    /**
     * Downloads the newest receiver to the app cache. If the download fails, it uses the copy from an earlier
     * download. Throws if there is neither.
     */
    suspend fun fetch(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "receiver.apk")
        try {
            val part = File(context.cacheDir, "receiver.apk.part")
            val connection = URL(ReceiverDownload.URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            // GitHub sends "latest/download" to the file of the newest release (https to https).
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (!isApk(part)) throw IOException("Not an APK")
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
        } catch (e: IOException) {
            if (!isApk(file)) throw e
        }
        file
    }

    /** An APK is a ZIP file: it starts with "PK". The receiver is much larger than 10 KB. */
    private fun isApk(file: File): Boolean {
        if (!file.isFile || file.length() < 10_000) return false
        val head = ByteArray(2)
        file.inputStream().use { if (it.read(head) != 2) return false }
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    /**
     * The IPv4 address of the phone on the local network, or null if the phone is not on Wi-Fi.
     * Wi-Fi interfaces (wlan) come first, then the phone hotspot (ap, swlan), then any other local network.
     */
    fun localAddress(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { nif.name to it.hostAddress }
                }
        } catch (e: Exception) {
            emptyList()
        }
        fun rank(name: String) = when {
            name.startsWith("wlan") -> 0
            name.startsWith("ap") || name.startsWith("swlan") -> 1
            name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // mobile data: the box cannot reach it
            else -> 2
        }
        return candidates.filter { rank(it.first) < 9 }.minByOrNull { rank(it.first) }?.second
    }

    /** Starts the server on the first free port of [PORTS]. Returns null if no port is free. */
    fun serve(scope: CoroutineScope, file: File, onDownloaded: () -> Unit): Server? {
        for (port in PORTS) {
            val socket = try {
                ServerSocket(port)
            } catch (e: IOException) {
                continue
            }
            return Server(socket, scope.launch(Dispatchers.IO) { acceptLoop(socket, file, onDownloaded) })
        }
        return null
    }

    class Server internal constructor(private val socket: ServerSocket, private val job: Job) {
        val port: Int get() = socket.localPort

        fun stop() {
            job.cancel()
            runCatching { socket.close() }
        }
    }

    /** Accepts connections until [Server.stop] closes the socket. */
    private suspend fun acceptLoop(server: ServerSocket, file: File, onDownloaded: () -> Unit) = coroutineScope {
        while (isActive) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break // The server socket closed.
            }
            launch(Dispatchers.IO) { handle(client, file, onDownloaded) }
        }
    }

    /**
     * Answers one HTTP request. Every path except /favicon.ico gets the APK, so "192.168.1.23:8080" alone works.
     * The file goes out as an attachment named receiver.apk, with the APK media type, so that Downloader offers to
     * install it and a browser saves it.
     */
    private fun handle(client: Socket, file: File, onDownloaded: () -> Unit) {
        client.use { socket ->
            try {
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                // Skip the headers, up to the empty line.
                var count = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty() || ++count > 100) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                val out = socket.getOutputStream()
                if (method != "GET" && method != "HEAD") {
                    out.write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                if (path.startsWith("/favicon")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.android.package-archive\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"receiver.apk\"\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray())
                if (method == "GET") {
                    file.inputStream().use { it.copyTo(out) }
                    out.flush()
                    onDownloaded()
                }
                out.flush()
            } catch (e: IOException) {
                // The box closed the connection. Nothing to do.
            }
        }
    }

    /** Reads one line of at most 8 KB, without the line end. Null at the end of the stream. */
    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }
}
