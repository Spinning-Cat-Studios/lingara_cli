// One cloudflared incarnation (ADR 16.10.26ae D8): spawned with a free
// loopback `--metrics` port, its quick tunnel's hostname read from
// `/quicktunnel` or its log, and its readiness probed through the tunnel.
package com.getlingara.cli.kotlin.supervise

import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque

private const val WAIT_MILLIS = 30_000L
private const val KEPT_LINES = 20
private const val DEFAULT_PROBE = "https://{hostname}/"

/** One cloudflared incarnation. */
class Tunnel private constructor(private val group: Group, private val metrics: String) {
    private val lines = ArrayDeque<String>()

    @Volatile private var loggedHost: String? = null
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(2)).build()

    fun alive(): Boolean = group.exited() == null

    /** The last lines cloudflared wrote, for `tunnel_failed`'s reason. */
    fun tail(): String = synchronized(lines) { lines.joinToString("\n") }

    private fun get(url: String): HttpResponse<ByteArray>? = runCatching {
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    }.getOrNull()

    private fun quicktunnel(): String? = get("http://$metrics/quicktunnel")?.let { Json.parse(it.body()) }?.path("hostname")?.textValue()?.takeIf { it.isNotEmpty() }

    /** The quick tunnel's hostname: metrics first, the log line second. */
    fun hostname(stopped: () -> Boolean): String? {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline && alive() && !stopped()) {
            (quicktunnel() ?: loggedHost)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    /** Whether the probe reached the app through the tunnel: any answer but a `530`, once a second, for at most 30 s. */
    fun ready(host: String, template: String?, stopped: () -> Boolean): Boolean {
        val url = (template ?: DEFAULT_PROBE).replace("{hostname}", host).replace("{metrics}", metrics)
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline && alive() && !stopped()) {
            if (get(url)?.statusCode()?.let { it != 530 } == true) return true
            Thread.sleep(1000)
        }
        return false
    }

    fun stop() = group.stop(Duration.ofSeconds(5))

    private fun keep(line: String) {
        if (loggedHost == null) Cloudflared.hostnameIn(line)?.let { loggedHost = it }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > KEPT_LINES) lines.removeFirst()
        }
    }

    companion object {
        /** `cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>`. */
        fun spawn(binary: Path, port: Int): Tunnel {
            val metrics = "127.0.0.1:${Cloudflared.freePort()}"
            val builder = ProcessBuilder(binary.toString(), "tunnel", "--url", "http://127.0.0.1:$port", "--metrics", metrics)
                .redirectInput(Supervise.NULL_INPUT).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val group = try {
                Supervise.start(builder, null, null)
            } catch (e: IOException) {
                throw Fail(Exit.TUNNEL_FAILED, "tunnel_failed", "cloudflared: ${e.message}")
            }
            val tunnel = Tunnel(group, metrics)
            val reader = Thread { group.process.errorStream.bufferedReader().useLines { it.forEach(tunnel::keep) } }
            reader.isDaemon = true
            reader.start()
            return tunnel
        }
    }
}
