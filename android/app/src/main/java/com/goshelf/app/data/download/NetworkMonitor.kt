package com.goshelf.app.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Lightweight connectivity checks for long-running downloads. */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** True when the app's default network reports internet capability. */
    fun isAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Suspends until a usable default network appears. */
    suspend fun awaitAvailable() {
        if (isAvailable()) return
        var registered: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            continuation.isActive
                        ) {
                            continuation.resume(Unit)
                        }
                    }
                }
                registered = callback
                connectivityManager.registerDefaultNetworkCallback(callback)
            }
        } finally {
            registered?.let { callback ->
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }
}
