package app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking

// The JDK's own server, on the kit's primitive: LingaraApp.handle does the method, size,
// signature, decode, dispatch and validation; this only moves bytes.
fun main() {
    val secret = System.getenv("LINGARA_APP_SECRET").orEmpty()
    require(secret.isNotEmpty()) { "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`" }
    val app = createApp(*secret.split(",").toTypedArray())
    val port = System.getenv("PORT")?.toInt() ?: {{port}}
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange ->
        val reply =
            runBlocking {
                app.handle(exchange.requestMethod, exchange.requestHeaders) { limit -> exchange.requestBody.readNBytes(limit) }
            }
        reply.contentType?.let { exchange.responseHeaders.set("content-type", it) }
        exchange.sendResponseHeaders(reply.status, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
        exchange.responseBody.use { it.write(reply.body) }
    }
    server.start()
    println("{{name}} is listening on http://127.0.0.1:$port/")
}
