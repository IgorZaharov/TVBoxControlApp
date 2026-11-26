package com.tvboxremote.app.data

/**
 * The order of the device list: the devices that the phone connected to before come first and are highlighted.
 * The most recent is first. After them come the paired devices that are not in the recent list, then the others.
 * The sort is stable, so the other devices keep their order.
 */
object DeviceOrder {
    /** [recent]: the keys of the recent devices, the most recent first. [paired]: true for a device with a stored pairing. */
    fun <T> knownFirst(items: List<T>, recent: List<String>, key: (T) -> String, paired: (T) -> Boolean = { false }): List<T> =
        items.sortedBy { rank(key(it), recent, paired(it)) }

    /** True if the device goes in the highlighted group at the top. */
    fun isKnown(key: String, recent: List<String>, paired: Boolean = false): Boolean = rank(key, recent, paired) <= recent.size

    private fun rank(key: String, recent: List<String>, paired: Boolean): Int {
        val index = recent.indexOf(key)
        return when {
            index >= 0 -> index
            paired -> recent.size
            else -> recent.size + 1
        }
    }
}
