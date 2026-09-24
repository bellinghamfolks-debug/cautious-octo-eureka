package com.abdullah.visionbridge.data.gemini

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GeminiHttpTimingsTest {
    @Test fun realSseFactoryRetainsUploadAndHeaderInstrumentation() {
        ServerSocket(0).use { server ->
            server.soTimeout=5_000
            val failure=AtomicReference<Throwable?>(null)
            val responder=thread(isDaemon=true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout=5_000
                        val reader=socket.getInputStream().bufferedReader()
                        var length=0
                        while(true) {
                            val line=reader.readLine() ?: error("missing request headers")
                            if(line.isEmpty()) break
                            if(line.startsWith("Content-Length:",true)) length=line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { check(reader.read()>=0) }
                        val body="data: synthetic text\n\n"
                        val response="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray());socket.getOutputStream().flush()
                    }
                } catch(e:Throwable) { failure.set(e) }
            }
            val timings=GeminiHttpTimings()
            val client=OkHttpClient.Builder().eventListener(timings).build()
            val done=CountDownLatch(1)
            val factory=Call.Factory { client.newCall(it) }
            val source=EventSources.createFactory(factory).newEventSource(
                Request.Builder().url("http://127.0.0.1:${server.localPort}/synthetic")
                    .post("fixture".toRequestBody()).build(),object:EventSourceListener() {
                    override fun onEvent(eventSource:EventSource,id:String?,type:String?,data:String) { timings.firstModelText() }
                    override fun onClosed(eventSource:EventSource) { timings.responseCompleted();done.countDown() }
                    override fun onFailure(eventSource:EventSource,t:Throwable?,response:Response?) {
                        failure.set(t ?: AssertionError("SSE failed"));done.countDown()
                    }
                })
            try {
                assertTrue("SSE did not close",done.await(5,TimeUnit.SECONDS))
                responder.join(1000)
                assertNull(failure.get())
                val fields=timings.fields()
                assertEquals(7L,fields["uploadedBytes"])
                assertEquals(200,fields["httpCode"])
                assertNotNull(fields["uploadMs"])
                assertNotNull(fields["uploadToHeadersMs"])
                assertNotNull(fields["requestToFirstModelTextMs"])
                assertNotNull(fields["modelStreamMs"])
                assertEquals(true,fields["httpCompleted"])
                assertFalse(fields.toString().contains("synthetic"))
            } finally {
                source.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
            }
        }
    }
}
