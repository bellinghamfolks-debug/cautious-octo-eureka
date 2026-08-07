package com.abdullah.visionbridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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

    /**
     * Downstream kilobits per second the network the last request bound to reported for itself,
     * or null when nothing was bound.
     *
     * Published rather than returned because the encoder that needs it sits several layers below
     * the call that binds. It is the one number that separates "the network is broken" from "the
     * network is carrying 14 kbps and the picture is too big for it" — a distinction one field
     * session turned on entirely.
     */
    @Volatile
    var boundLinkKbps: Int? = null
        private set

    suspend fun <T> withNetwork(forceCellular: Boolean, block: suspend (Network?) -> T): T {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        if (!forceCellular) {
            DiagnosticHub.record(
                "CELLULAR_NETWORK_ACQUISITION_SKIPPED",
                trace.fieldsOrEmpty(
                    mapOf<String, Any?>("analysisBudgetMs" to ANALYSIS_BUDGET_MS) +
                        describeNetwork(null, "default"),
                ),
            )
            boundLinkKbps = null
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
                mapOf<String, Any?>(
                    "durationMs" to
                        (SystemClock.elapsedRealtimeNanos() - acquisitionStarted) / 1_000_000.0,
                    "networkHandle" to lease.network.networkHandle,
                ) +
                    // Both sides of the comparison, at the moment of binding. Five field sessions
                    // ran with cellular forced and none without, so "cellular caused the freeze"
                    // could not be told from "the session froze"; recording what the bound network
                    // could actually do — and what the default network could — is what turns the
                    // next bundle into evidence instead of another correlation.
                    describeNetwork(lease.network, "bound") + describeNetwork(null, "default"),
            ),
        )
        boundLinkKbps = connectivityManager
            ?.getNetworkCapabilities(lease.network)
            ?.linkDownstreamBandwidthKbps
            ?.takeIf { it > 0 }
        return try {
            runWithinAnalysisBudget(trace) { block(lease.network) }
        } finally {
            boundLinkKbps = null
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
        // Stated as an instant, not a countdown. `withTimeout` is kept as the fast path because it
        // cancels promptly while the process is running, but the deadline is what the coordinator
        // checks on every arriving frame, so a request cannot outlive its budget just because
        // every scheduler that was watching it stopped at the same moment.
        val deadline = AnalysisDeadline(ANALYSIS_BUDGET_MS, SystemClock::elapsedRealtime)
        return try {
            withTimeout(ANALYSIS_BUDGET_MS) { block() }
        } catch (error: TimeoutCancellationException) {
            DiagnosticHub.record(
                "CLOUD_ANALYSIS_BUDGET_EXCEEDED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "budgetMs" to ANALYSIS_BUDGET_MS,
                        "elapsedMs" to deadline.elapsedMs(),
                        "overrunMs" to deadline.overrunMs(),
                    ),
                ),
            )
            throw IllegalStateException(
                "تجاوز التحليل المهلة المحددة، لذلك انتقل VisionBridge إلى أحدث لقطة.",
                error,
            )
        }
    }

    /**
     * Acquires a cellular network to bind the request's sockets to.
     *
     * `onAvailable` means the radio came up, not that anything can be sent over it. A network that
     * is still authenticating, or sitting behind a captive portal, satisfies the old request and
     * then swallows the request until something times out — indistinguishable, from inside the app,
     * from a slow model. Asking for `NET_CAPABILITY_VALIDATED` asks for a network that has been
     * proven to carry traffic.
     *
     * The unvalidated fallback exists because a network that never reports validation is still
     * usually usable, and a blind user who has chosen cellular deliberately should not be left
     * with nothing because a carrier does not set a flag. Which path was taken is recorded, so a
     * bundle can show whether the fallback is load-bearing.
     */
    private suspend fun acquireCellularNetwork(): NetworkLease {
        val validated = runCatching {
            withTimeout(VALIDATED_REQUEST_TIMEOUT_MS) { requestNetwork(requireValidated = true) }
        }
        validated.getOrNull()?.let { return it }

        DiagnosticHub.record(
            "CELLULAR_NETWORK_VALIDATION_FALLBACK",
            mapOf(
                "timeoutMs" to VALIDATED_REQUEST_TIMEOUT_MS,
                "reason" to (validated.exceptionOrNull()?.javaClass?.simpleName ?: "timeout"),
            ),
        )
        return withTimeout(REQUEST_TIMEOUT_MS - VALIDATED_REQUEST_TIMEOUT_MS) {
            requestNetwork(requireValidated = false)
        }
    }

    private suspend fun requestNetwork(requireValidated: Boolean): NetworkLease =
        suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply {
                    if (requireValidated) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }
                }
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (continuation.isActive) {
                        DiagnosticHub.record(
                            "CELLULAR_NETWORK_CAPABILITIES",
                            mapOf(
                                "requiredValidated" to requireValidated,
                                "validated" to connectivityManager
                                    ?.getNetworkCapabilities(network)
                                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                                "networkHandle" to network.networkHandle,
                            ),
                        )
                        continuation.resume(NetworkLease(network, this))
                    }
                }

                override fun onUnavailable() {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("تعذر الاتصال بالإنترنت عبر بيانات الجوال")
                        )
                    }
                }
            }

            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            val timeout = if (requireValidated) {
                VALIDATED_REQUEST_TIMEOUT_MS
            } else {
                REQUEST_TIMEOUT_MS - VALIDATED_REQUEST_TIMEOUT_MS
            }
            connectivityManager.requestNetwork(request, callback, timeout.toInt())
        }

    /**
     * Everything the platform will say about one network, flattened under a [prefix].
     *
     * Deliberately capability-level rather than identity-level: transports, whether the network has
     * been validated, whether it is suspended or behind a captive portal, and the bandwidth the
     * system believes it has. No carrier name, no subscriber identity, no addresses — none of which
     * would help a diagnosis and all of which would be a cost to carry in a file the user sends.
     *
     * A null [network] describes the process default, which is the network everything would have
     * used had cellular not been forced.
     */
    private fun describeNetwork(network: Network?, prefix: String): Map<String, Any?> {
        val target = network ?: connectivityManager?.activeNetwork
        val capabilities = target?.let { connectivityManager?.getNetworkCapabilities(it) }
            ?: return mapOf("${prefix}NetworkPresent" to false)
        return mapOf(
            "${prefix}NetworkPresent" to true,
            "${prefix}Handle" to target.networkHandle,
            "${prefix}Cellular" to
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            "${prefix}Wifi" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            "${prefix}Vpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            "${prefix}Validated" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "${prefix}Internet" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            // A captive portal is the failure that looks exactly like a slow model from inside the
            // app: the socket connects, the request is swallowed, and nothing errors.
            "${prefix}CaptivePortal" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            "${prefix}NotSuspended" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            "${prefix}NotMetered" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            "${prefix}DownstreamKbps" to capabilities.linkDownstreamBandwidthKbps,
            "${prefix}UpstreamKbps" to capabilities.linkUpstreamBandwidthKbps,
            "${prefix}SignalStrength" to
                if (Build.VERSION.SDK_INT >= 29) capabilities.signalStrength else null,
        )
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

        /** Two thirds of the acquisition budget spent insisting on a network that works. */
        const val VALIDATED_REQUEST_TIMEOUT_MS = 10_000L
        const val ANALYSIS_BUDGET_MS = 24_000L
    }
}
