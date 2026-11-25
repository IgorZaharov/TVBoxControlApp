package com.tvboxremote.receiver

import java.net.Inet4Address
import java.net.NetworkInterface

/** Live status of the receiver, for the status line in [MainActivity]. */
object ReceiverState {
    @Volatile
    var running: Boolean = false

    /** The TCP port, or -1 if the server is not listening. */
    @Volatile
    var port: Int = -1

    @Volatile
    var clientCountProvider: () -> Int = { 0 }

    /** The first IPv4 address of an active, non-loopback network interface. */
    fun localIpv4Address(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
