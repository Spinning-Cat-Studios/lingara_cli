// The one seam every request crosses: `java.net.http.HttpClient` in
// production (ADR 16.10.26af D1, never the `lingara` API libraries), a
// scripted fake in the unit tests.
package com.getlingara.cli.kotlin.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One request on the wire, already spelled: method, absolute URL, headers and an optional body. */
data class WireRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray)

class WireResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** A thrown [IOException] is a connection failure: contract B2 rule 7. */
fun interface Transport {
    fun send(request: WireRequest): WireResponse
}

class JdkTransport : Transport {
    // HTTP/1.1 only: the JDK's default first offers an h2c upgrade on every
    // plain-HTTP request. No redirects: an unexpected 3xx is B2 rule 8's.
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    override fun send(request: WireRequest): WireResponse {
        val body = if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(request.body)
        val builder = try {
            HttpRequest.newBuilder(URI.create(request.url)).timeout(Duration.ofSeconds(60)).method(request.method, body)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "bad URL")
        }
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: InterruptedException) {
            throw IOException("interrupted", e)
        }
        val headers = response.headers().map().flatMap { (k, vs) -> vs.map { k to it } }
        return WireResponse(response.statusCode(), headers, response.body() ?: ByteArray(0))
    }
}
