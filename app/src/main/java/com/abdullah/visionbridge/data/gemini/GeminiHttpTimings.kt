package com.abdullah.visionbridge.data.gemini

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Per-request measurements, with no URLs, addresses, headers or user content. */
class GeminiHttpTimings(private val clock: () -> Long = System::nanoTime) : EventListener() {
    private val started = clock()
    private val starts = mutableMapOf<String, Long>()
    private val durations = mutableMapOf<String, Double>()
    private var connectAttempts = 0
    private var connected = false
    private var uploadEnd: Long? = null
    private var headersAt: Long? = null
    private var firstTextAt: Long? = null
    private var completedAt: Long? = null
    private var uploadedBytes = 0L
    private var httpCode: Int? = null

    @Synchronized private fun begin(stage: String) { starts[stage] = clock() }
    @Synchronized private fun end(stage: String) {
        starts.remove(stage)?.let { durations[stage] = (durations[stage] ?: 0.0) + (clock()-it)/1e6 }
    }
    override fun dnsStart(call: Call, domainName: String) = begin("dnsMs")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = end("dnsMs")
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAttempts++; begin("connectIncludingTlsMs")
    }
    override fun secureConnectStart(call: Call) = begin("tlsMs")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = end("tlsMs")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = end("connectIncludingTlsMs")
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = end("connectIncludingTlsMs")
    @Synchronized override fun connectionAcquired(call: Call, connection: Connection) { connected = true }
    override fun requestBodyStart(call: Call) = begin("uploadMs")
    @Synchronized override fun requestBodyEnd(call: Call, byteCount: Long) {
        end("uploadMs"); uploadEnd=clock(); uploadedBytes=byteCount
    }
    @Synchronized override fun responseHeadersEnd(call: Call, response: Response) {
        headersAt=clock(); httpCode=response.code
    }
    @Synchronized fun firstModelText() { if(firstTextAt==null) firstTextAt=clock() }
    @Synchronized fun responseCompleted() { completedAt=clock() }
    @Synchronized fun fields(): Map<String, Any?> = durations.toMap()+mapOf(
        "connectAttempts" to connectAttempts,
        "connectionReused" to (connected && connectAttempts==0),
        "uploadedBytes" to uploadedBytes,
        "httpCode" to httpCode,
        // Includes server compute AND transit; neither can be isolated without server timestamps.
        "uploadToHeadersMs" to elapsed(uploadEnd,headersAt),
        "headersToFirstModelTextMs" to elapsed(headersAt,firstTextAt),
        "requestToFirstModelTextMs" to elapsed(started,firstTextAt),
        "modelStreamMs" to elapsed(firstTextAt,completedAt),
        "httpCompleted" to (completedAt!=null),
    )
    private fun elapsed(from: Long?, to: Long?): Double? =
        if(from!=null && to!=null) (to-from).coerceAtLeast(0)/1e6 else null
}
