package app

import com.getlingara.apps.kotlin.ktor.lingaraApp
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun main() {
    val secret = System.getenv("LINGARA_APP_SECRET").orEmpty()
    require(secret.isNotEmpty()) { "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`" }
    val app = createApp(*secret.split(",").toTypedArray())
    val port = System.getenv("PORT")?.toInt() ?: 8787
    println("demo is listening on http://127.0.0.1:$port/")
    // Route.lingaraApp reads the raw body itself, so content negotiation never parses it.
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            route("/") { lingaraApp(app) }
        }
    }.start(wait = true)
}
