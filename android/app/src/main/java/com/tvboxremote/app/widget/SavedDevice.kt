package com.tvboxremote.app.widget

/**
 * A device that the app connected to at least once. The home screen widget lists these.
 * [id] is "wifi:<host>:<port>" or "bt:<address>".
 */
data class SavedDevice(val id: String, val name: String) {
    val isBluetooth: Boolean get() = id.startsWith(BT)

    /** The box address for Wi-Fi. An IPv6 address has colons too, so the port is after the last one. */
    val host: String? get() = if (id.startsWith(WIFI)) id.removePrefix(WIFI).substringBeforeLast(':') else null

    val port: Int? get() = if (id.startsWith(WIFI)) id.substringAfterLast(':').toIntOrNull() else null

    /** The Bluetooth address. */
    val address: String? get() = if (isBluetooth) id.removePrefix(BT) else null

    companion object {
        private const val WIFI = "wifi:"
        private const val BT = "bt:"

        /** The widget keeps at most this many devices. */
        const val MAX = 8

        fun wifi(host: String, port: Int, name: String) = SavedDevice("$WIFI$host:$port", name)

        fun bluetooth(address: String, name: String) = SavedDevice("$BT$address", name)

        /** One device per line: id, a tab, the name. */
        fun encode(devices: List<SavedDevice>): String =
            devices.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }

        fun decode(text: String?): List<SavedDevice> =
            text.orEmpty().lines().mapNotNull { line ->
                val id = line.substringBefore('\t')
                val name = line.substringAfter('\t', "")
                if ((id.startsWith(WIFI) || id.startsWith(BT)) && name.isNotBlank()) SavedDevice(id, name) else null
            }.distinctBy { it.id }

        /** Puts [device] first (the most recent), without a second entry for the same id. */
        fun remember(devices: List<SavedDevice>, device: SavedDevice): List<SavedDevice> =
            (listOf(device) + devices.filterNot { it.id == device.id }).take(MAX)
    }
}
