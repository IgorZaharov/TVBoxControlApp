package com.tvboxremote.receiver.server

import com.tvboxremote.receiver.protocol.ErrorCodes
import com.tvboxremote.receiver.protocol.ServerMessages
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Receives the lines of one connection. Called on the connection thread. */
interface LineHandler {
    fun onLine(line: String)
    fun onClosed()
}

/** One phone connection, as the session sees it. */
interface ClientLink {
    /** Sends one line. Thread-safe. Do not call on the main thread. */
    fun send(line: String)
    fun close()
}

/**
 * A TCP server with one thread for each connection. Each line goes to the
 * [LineHandler] that [handlerFactory] makes for the connection.
 */
class ReceiverServer(
    private val preferredPort: Int = DEFAULT_PORT,
    private val maxClients: Int = MAX_CLIENTS,
    private val handlerFactory: (ClientLink) -> LineHandler,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val clients: MutableSet<Client> = ConcurrentHashMap.newKeySet()

    val port: Int get() = serverSocket?.localPort ?: -1
    val clientCount: Int get() = clients.size

    /** Opens the server socket and starts to accept. Blocks briefly. Returns the port. */
    @Throws(IOException::class)
    fun start(): Int {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (e: IOException) {
            // The preferred port is in use. Any free port works, because Bonjour announces it.
            ServerSocket(0)
        }
        serverSocket = socket
        thread(name = "receiver-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            if (clients.size >= maxClients) {
                reject(connection)
                continue
            }
            val client = Client(connection)
            clients.add(client)
            thread(name = "receiver-client", isDaemon = true) { client.run() }
        }
    }

    private fun reject(connection: Socket) {
        try {
            connection.use {
                val line = ServerMessages.error(ErrorCodes.TOO_MANY_CLIENTS, "Too many phones are connected.")
                it.getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: IOException) {
        }
    }

    private inner class Client(private val socket: Socket) : ClientLink {
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()

        @Volatile
        private var output: OutputStream? = null

        override fun send(line: String) {
            if (closed.get()) return
            val out = output ?: return
            try {
                synchronized(writeLock) {
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }

        fun run() {
            var handler: LineHandler? = null
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = IDLE_TIMEOUT_MS
                output = socket.getOutputStream()
                val reader = LineReader(BufferedInputStream(socket.getInputStream()), MAX_LINE_BYTES)
                handler = handlerFactory(this)
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handler.onLine(line)
                }
            } catch (_: IOException) {
                // Timeout, line too long, or the connection dropped. Close below.
            } finally {
                close()
                clients.remove(this)
                handler?.onClosed()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 6470
        const val MAX_CLIENTS = 4
        const val MAX_LINE_BYTES = 4 * 1024

        /** Phones send a ping every 5 seconds. Close a connection after 30 seconds of silence. */
        const val IDLE_TIMEOUT_MS = 30_000
    }
}
