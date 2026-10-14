package com.shadowpheonix.tradingdashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlertNetwork(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val available = MutableStateFlow(AlertNetworkState(false))
    val online = available.asStateFlow()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        // API 26+ follows onAvailable with capabilities, including at registration.
        // Use those ordered arguments: synchronous queries here can be outdated.
        override fun onAvailable(network: Network) { available.value = AlertNetworkState(false, network.networkHandle) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            available.value = AlertNetworkState(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), network.networkHandle)
        }
        override fun onLost(network: Network) {
            if (available.value.networkId == network.networkHandle) available.value = AlertNetworkState(false)
        }
    }
    init {
        manager.registerDefaultNetworkCallback(callback)
    }
    override fun close() { manager.unregisterNetworkCallback(callback) }
}
