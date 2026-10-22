// The unit tests' world: a scripted transport, a terminal, a clock that
// never sleeps and an in-memory keychain holding the conformance fixture's
// developer. Every seam `App` has, faked once (ADR 16.10.26ad D11).
package com.getlingara.cli.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.App
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.SCOPES
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Env
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Terminal
import com.getlingara.cli.kotlin.http.Bases
import com.getlingara.cli.kotlin.http.Clock
import com.getlingara.cli.kotlin.http.Http
import com.getlingara.cli.kotlin.http.Identity
import com.getlingara.cli.kotlin.http.Transport
import com.getlingara.cli.kotlin.http.WireRequest
import com.getlingara.cli.kotlin.http.WireResponse
import com.getlingara.cli.kotlin.output.Output
import com.getlingara.cli.kotlin.store.CredentialStore
import com.getlingara.cli.kotlin.store.FileStore
import com.getlingara.cli.kotlin.store.Mode
import com.getlingara.cli.kotlin.store.ProfileRecord
import com.getlingara.cli.kotlin.store.StoreUnavailable
import com.getlingara.cli.kotlin.store.Stores
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path

const val EMAIL = "dev@conformance.invalid"

/** Requests answered in order; each answer names the `METHOD /path` it expects, and anything else is a `599`, as the conformance mock does. */
class Script : Transport {
    private val answers = ArrayDeque<Pair<String, WireResponse>>()
    val log = mutableListOf<WireRequest>()

    fun then(expect: String, status: Int, body: JsonNode? = null): Script = apply {
        answers.addLast(expect to WireResponse(status, emptyList(), body?.let { Json.write(it).toByteArray() } ?: ByteArray(0)))
    }

    fun sent(): List<String> = log.map { "${it.method} ${pathOf(it.url)}" }

    private fun pathOf(url: String): String = url.substringAfter("://").let { r -> r.indexOf('/').let { if (it < 0) "/" else r.substring(it) } }

    override fun send(request: WireRequest): WireResponse {
        log.add(request)
        val got = "${request.method} ${pathOf(request.url)}"
        val (want, response) = answers.removeFirstOrNull() ?: throw IOException("connection reset")
        return if (got == want || got.substringBefore('?') == want) response else WireResponse(599, emptyList(), "expected $want".toByteArray())
    }
}

class FakeTerminal(private val interactive: Boolean, lines: List<String>) : Terminal {
    private val lines = ArrayDeque(lines)

    override fun interactive() = interactive

    override fun readLine(): String? = lines.removeFirstOrNull()
}

fun pipe() = FakeTerminal(false, emptyList())

fun tty(vararg lines: String) = FakeTerminal(true, lines.toList())

object FakeClock : Clock {
    override fun now() = 1_760_000_000L

    override fun sleep(secs: Long) = Unit
}

/** The in-memory store (D4), which can also play a keychain that is not there. */
class MemoryStore(private val unavailable: Boolean = false) : CredentialStore {
    val records = mutableMapOf<String, ProfileRecord>()

    private fun open() {
        if (unavailable) throw StoreUnavailable("no keychain here")
    }

    override fun load(profile: String): ProfileRecord? = records[profile].also { open() }

    override fun save(profile: String, record: ProfileRecord) {
        open()
        records[profile] = record
    }

    override fun delete(profile: String) {
        open()
        records.remove(profile)
    }
}

fun storedRecord() = ProfileRecord(
    developerId = "0b9c4f0e-0000-4000-8000-0000000000de",
    email = EMAIL,
    scopes = SCOPES,
    refreshToken = "lgr_rt_conformance_stored",
    accessToken = "lgr_at_conformance_stored",
    accessExpiresAt = "2099-01-01T00:00:00Z",
)

/** An app signed in as the fixture developer, over [script]. */
fun app(script: Script, terminal: Terminal): App {
    val keychain = MemoryStore().also { it.records["default"] = storedRecord() }
    val http = Http(script, FakeClock, Bases("http://mock", "http://mock"), Identity("lingara-cli/0.0.0 (kotlin; test)", "en-US"))
    val stores = Stores(Mode.KEYCHAIN, keychain, FileStore(Path.of("/nonexistent")))
    return App(http, Env(emptyMap()), stores, terminal, { false }, Assets.endpoints, null)
}

/** What one call against a fresh context left: its result or refusal, stdout and stderr. */
class Ran<T>(val result: Result<T>, val stdout: String, val stderr: String)

fun <T> withCtx(app: App, json: Boolean = false, body: (Ctx) -> T): Ran<T> {
    val err = ByteArrayOutputStream()
    val out = Output(PrintStream(err, true, Charsets.UTF_8), json)
    val result = runCatching { body(Ctx(app, out, "default", false)) }
    return Ran(result, out.stdout.toString(), err.toString(Charsets.UTF_8))
}
