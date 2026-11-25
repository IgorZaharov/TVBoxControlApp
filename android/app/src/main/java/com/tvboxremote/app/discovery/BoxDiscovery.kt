package com.tvboxremote.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** A box on the network. [receiverPort] is set if the box receiver app runs on the same IP address. */
data class TvBox(
    val host: String,
    val port: Int,
    val name: String,
    val receiverPort: Int? = null,
    val manual: Boolean = false,
)

/**
 * Finds Android TV boxes (`_androidtvremote2._tcp`) and box receivers
 * (`_tvboxremote._tcp`) with Android NSD. It merges them by IP address.
 */
class BoxDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val tvs = LinkedHashMap<String, TvBox>()        // by host
    private val receivers = HashMap<String, Int>()          // host -> port
    private val serviceHosts = HashMap<String, String>()    // "type/name" -> host
    private var manual: List<TvBox> = emptyList()

    private val _boxes = MutableStateFlow<List<TvBox>>(emptyList())
    val boxes: StateFlow<List<TvBox>> = _boxes.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val listeners = ArrayList<NsdManager.DiscoveryListener>()

    // Before Android 14, NSD resolves one service at a time. This queue serializes resolves.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun setManualBoxes(list: List<TvBox>) {
        main.post { manual = list; publish() }
    }

    fun start() {
        if (listeners.isNotEmpty()) return
        _searching.value = true
        for (type in listOf(TYPE_ATV, TYPE_RECEIVER)) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { enqueueResolve(info) }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { lost(info) }
                }
            }
            listeners += listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        }
    }

    fun stop() {
        for (l in listeners) runCatching { nsd.stopServiceDiscovery(l) }
        listeners.clear()
        _searching.value = false
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    resolved(serviceInfo)
                    resolveNext()
                }
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { resolving = false; resolveNext() }
    }

    @Suppress("DEPRECATION")
    private fun resolved(info: NsdServiceInfo) {
        val address = info.host ?: return
        // A link-local IPv6 address carries a "%scope" suffix. Sockets accept that form.
        val host = address.hostAddress ?: return
        if (address !is Inet4Address && tvs.containsKey(host)) return
        val type = normalizeType(info.serviceType)
        serviceHosts["$type/${info.serviceName}"] = host
        if (type == TYPE_ATV) {
            tvs[host] = TvBox(host = host, port = info.port, name = info.serviceName)
        } else {
            receivers[host] = info.port
        }
        publish()
    }

    private fun lost(info: NsdServiceInfo) {
        val type = normalizeType(info.serviceType)
        val host = serviceHosts.remove("$type/${info.serviceName}") ?: return
        if (type == TYPE_ATV) tvs.remove(host) else receivers.remove(host)
        publish()
    }

    private fun publish() {
        val found = tvs.values.map { it.copy(receiverPort = receivers[it.host]) }
        val foundHosts = found.map { it.host }.toSet()
        val manualOnly = manual.filter { it.host !in foundHosts }.map { it.copy(receiverPort = receivers[it.host] ?: it.receiverPort) }
        _boxes.value = (found + manualOnly).sortedBy { it.name.lowercase() }
    }

    /** NSD reports types as "_x._tcp." or "._x._tcp" depending on the version. */
    private fun normalizeType(t: String): String {
        val trimmed = t.trim('.')
        return if (trimmed.contains("androidtvremote2")) TYPE_ATV else TYPE_RECEIVER
    }

    /** The receiver port on [host], if a receiver was found there. */
    fun receiverPortFor(host: String): Int? = receivers[host]

    companion object {
        const val TYPE_ATV = "_androidtvremote2._tcp"
        const val TYPE_RECEIVER = "_tvboxremote._tcp"
        const val DEFAULT_ATV_PORT = 6466
        const val DEFAULT_RECEIVER_PORT = 6470
    }
}
