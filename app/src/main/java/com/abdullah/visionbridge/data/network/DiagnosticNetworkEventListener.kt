package com.abdullah.visionbridge.data.network

import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Per-call OkHttp telemetry without recording authorization headers or the API-key query/header. */
class DiagnosticNetworkEventListener(
    private val trace: DiagnosticTrace?,
) : EventListener() {
    private var callStartedAtNanos: Long = 0L

    override fun callStart(call: Call) {
        callStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        record("OKHTTP_CALL_STARTED", mapOf(
            "host" to call.request().url.host,
            "method" to call.request().method,
        ))
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        record("OKHTTP_PROXY_SELECTION_STARTED", mapOf("host" to url.host))
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        record("OKHTTP_PROXY_SELECTION_COMPLETED", mapOf(
            "host" to url.host,
            "proxies" to proxies.map { it.type().name },
        ))
    }

    override fun dnsStart(call: Call, domainName: String) {
        record("OKHTTP_DNS_STARTED", mapOf("domain" to domainName))
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        record("OKHTTP_DNS_COMPLETED", mapOf(
            "domain" to domainName,
            "addresses" to inetAddressList.mapNotNull { it.hostAddress },
            "addressCount" to inetAddressList.size,
        ))
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        record("OKHTTP_CONNECT_STARTED", socketFields(inetSocketAddress, proxy))
    }

    override fun secureConnectStart(call: Call) {
        record("OKHTTP_TLS_STARTED")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        record("OKHTTP_TLS_COMPLETED", handshakeFields(handshake))
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        record(
            "OKHTTP_CONNECT_COMPLETED",
            socketFields(inetSocketAddress, proxy) + mapOf("protocol" to protocol?.toString()),
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        DiagnosticHub.failure(
            "OKHTTP_CONNECT",
            ioe,
            fields(socketFields(inetSocketAddress, proxy) + mapOf("protocol" to protocol?.toString())),
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        record("OKHTTP_CONNECTION_ACQUIRED", mapOf(
            "protocol" to connection.protocol().toString(),
            "route" to connection.route().socketAddress().toString(),
            "multiplexed" to connection.isMultiplexed,
            "handshake" to handshakeFields(connection.handshake()),
        ))
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        record("OKHTTP_CONNECTION_RELEASED", mapOf(
            "protocol" to connection.protocol().toString(),
            "route" to connection.route().socketAddress().toString(),
        ))
    }

    override fun requestHeadersStart(call: Call) {
        record("OKHTTP_REQUEST_HEADERS_STARTED")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        record("OKHTTP_REQUEST_HEADERS_COMPLETED", mapOf(
            "method" to request.method,
            "host" to request.url.host,
            "contentType" to request.body?.contentType()?.toString(),
            "declaredContentLength" to runCatching { request.body?.contentLength() }.getOrNull(),
        ))
    }

    override fun requestBodyStart(call: Call) {
        record("OKHTTP_REQUEST_BODY_STARTED")
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        record("OKHTTP_REQUEST_BODY_COMPLETED", mapOf("uploadedBytes" to byteCount))
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        DiagnosticHub.failure("OKHTTP_REQUEST", ioe, fields())
    }

    override fun responseHeadersStart(call: Call) {
        record("OKHTTP_RESPONSE_HEADERS_STARTED")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        record("OKHTTP_RESPONSE_HEADERS_COMPLETED", mapOf(
            "httpCode" to response.code,
            "protocol" to response.protocol.toString(),
            "contentType" to response.header("content-type"),
            "contentLength" to response.header("content-length"),
            "server" to response.header("server"),
        ))
    }

    override fun responseBodyStart(call: Call) {
        record("OKHTTP_RESPONSE_BODY_STARTED")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        record("OKHTTP_RESPONSE_BODY_COMPLETED", mapOf("downloadedBytes" to byteCount))
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        DiagnosticHub.failure("OKHTTP_RESPONSE", ioe, fields())
    }

    override fun callEnd(call: Call) {
        record("OKHTTP_CALL_COMPLETED")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        DiagnosticHub.failure("OKHTTP_CALL", ioe, fields())
    }

    override fun canceled(call: Call) {
        record("OKHTTP_CALL_CANCELLED")
    }

    private fun record(type: String, extra: Map<String, Any?> = emptyMap()) {
        DiagnosticHub.record(type, fields(extra))
    }

    private fun fields(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val callElapsedMs = if (callStartedAtNanos == 0L) null else
            (SystemClock.elapsedRealtimeNanos() - callStartedAtNanos) / 1_000_000.0
        val base = mapOf("callElapsedMs" to callElapsedMs) + extra
        return trace?.fields(base) ?: base
    }

    private fun socketFields(address: InetSocketAddress, proxy: Proxy): Map<String, Any?> = mapOf(
        "remoteHost" to address.hostString,
        "remoteAddress" to address.address?.hostAddress,
        "remotePort" to address.port,
        "proxyType" to proxy.type().name,
        "proxyAddress" to proxy.address()?.toString(),
    )

    private fun handshakeFields(handshake: Handshake?): Map<String, Any?> = mapOf(
        "tlsVersion" to handshake?.tlsVersion?.javaName,
        "cipherSuite" to handshake?.cipherSuite?.javaName,
        "peerCertificateCount" to handshake?.peerCertificates?.size,
        "localCertificateCount" to handshake?.localCertificates?.size,
    )

    class Factory : EventListener.Factory {
        override fun create(call: Call): EventListener =
            DiagnosticNetworkEventListener(call.request().tag(DiagnosticTrace::class.java))
    }
}
