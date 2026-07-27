package com.abdullah.visionbridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CellularNetworkManager(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    suspend fun <T> withNetwork(forceCellular: Boolean, block: suspend (Network?) -> T): T {
        if (!forceCellular) return block(null)

        val lease = acquireCellularNetwork()
        return try {
            block(lease.network)
        } finally {
            runCatching { connectivityManager.unregisterNetworkCallback(lease.callback) }
        }
    }

    private suspend fun acquireCellularNetwork(): NetworkLease = withTimeout(REQUEST_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (continuation.isActive) continuation.resume(NetworkLease(network, this))
                }

                override fun onUnavailable() {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("تعذر الحصول على شبكة بيانات خلوية متصلة بالإنترنت")
                        )
                    }
                }
            }

            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            connectivityManager.requestNetwork(request, callback, REQUEST_TIMEOUT_MS.toInt())
        }
    }

    private data class NetworkLease(
        val network: Network,
        val callback: ConnectivityManager.NetworkCallback,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
