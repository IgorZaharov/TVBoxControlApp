package com.tvboxremote.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the receiver on the local network as a Bonjour (mDNS) service. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name.take(MAX_SERVICE_NAME)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Announced as ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Announcement failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Removal of the announcement failed: $errorCode")
            }
        }
        listener = registration
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Announcement failed", e)
            listener = null
        }
    }

    fun unregister() {
        val current = listener ?: return
        listener = null
        try {
            nsd.unregisterService(current)
        } catch (e: IllegalArgumentException) {
            // The listener was not registered. Nothing to remove.
        }
    }

    companion object {
        const val SERVICE_TYPE = "_tvboxremote._tcp"
        private const val MAX_SERVICE_NAME = 63
        private const val TAG = "NsdAdvertiser"
    }
}
