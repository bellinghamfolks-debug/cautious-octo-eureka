package com.abdullah.visionbridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import kotlinx.coroutines.TimeoutCancellationException
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
            DiagnosticHub.record(
                "CELLULAR_NETWORK_ACQUISITION_SKIPPED",
                trace.fieldsOrEmpty(mapOf("analysisBudgetMs" to ANALYSIS_BUDGET_MS)),
            )
            return runWithinAnalysisBudget(trace) { block(null) }
        }

        val acquisitionStarted = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "CELLULAR_NETWORK_ACQUISITION_STARTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "timeoutMs" to REQUEST_TIMEOUT_MS,
                    "analysisBudgetMs" to ANALYSIS_BUDGET_MS,
                ),
            ),
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
            runWithinAnalysisBudget(trace) { block(lease.network) }
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

    /**
     * A single stalled Gemini request must never hold the one-frame cloud lane for 40–60 seconds.
     * The diagnostic trace showed 35.8 seconds to response headers and 43.5 seconds for one request.
     * Cancelling at a bounded budget lets the coordinator immediately promote the newest pending
     * frame instead of making the user wait behind an obsolete target.
     */
    private suspend fun <T> runWithinAnalysisBudget(
        trace: DiagnosticTrace?,
        block: suspend () -> T,
    ): T {
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            withTimeout(ANALYSIS_BUDGET_MS) { block() }
        } catch (error: TimeoutCancellationException) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            DiagnosticHub.record(
                "CLOUD_ANALYSIS_BUDGET_EXCEEDED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "budgetMs" to ANALYSIS_BUDGET_MS,
                        "elapsedMs" to elapsed,
                    ),
                ),
            )
            throw IllegalStateException(
                "تأخر رد التحليل أكثر من ${ANALYSIS_BUDGET_MS / 1_000} ثانية، فتم تجاوزه وتحضير أحدث لقطة.",
                error,
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
        const val ANALYSIS_BUDGET_MS = 24_000L
    }
}
