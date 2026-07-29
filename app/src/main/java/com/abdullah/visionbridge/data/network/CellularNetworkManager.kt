package com.abdullah.visionbridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CellularNetworkManager(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    suspend fun <T> withNetwork(forceCellular: Boolean, block: suspend (Network?) -> T): T {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        if (!forceCellular) {
            DiagnosticHub.record("CELLULAR_NETWORK_ACQUISITION_SKIPPED", trace.fieldsOrEmpty())
            return block(null)
        }

        val acquisitionStarted = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "CELLULAR_NETWORK_ACQUISITION_STARTED",
            trace.fieldsOrEmpty(mapOf("timeoutMs" to REQUEST_TIMEOUT_MS)),
        )
        val lease = try {
            acquireCellularNetwork()
        } catch (error: Throwable) {
            DiagnosticHub.failure(
                "CELLULAR_NETWORK_ACQUISITION",
                error,
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to
                            (SystemClock.elapsedRealtimeNanos() - acquisitionStarted) / 1_000_000.0,
                    ),
                ),
            )
            throw error
        }
        DiagnosticHub.record(
            "CELLULAR_NETWORK_ACQUIRED",
            trace.fieldsOrEmpty(
                mapOf(
                    "durationMs" to
                        (SystemClock.elapsedRealtimeNanos() - acquisitionStarted) / 1_000_000.0,
                    "networkHandle" to lease.network.networkHandle,
                ),
            ),
        )
        return try {
            block(lease.network)
        } finally {
            val releaseStarted = SystemClock.elapsedRealtimeNanos()
            val released = runCatching {
                connectivityManager.unregisterNetworkCallback(lease.callback)
            }.isSuccess
            DiagnosticHub.record(
                "CELLULAR_NETWORK_RELEASED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "released" to released,
                        "durationMs" to
                            (SystemClock.elapsedRealtimeNanos() - releaseStarted) / 1_000_000.0,
                    ),
                ),
            )
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

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra

    private data class NetworkLease(
        val network: Network,
        val callback: ConnectivityManager.NetworkCallback,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
