// The Kotlin kit's core (ADR 16.10.26af AC18, as AC16 and AC17): the confirm
// ritual, the store selection, the classifier's B2 rules, every
// `canvas-plans.json` vector, the B1 table, `interrupted` returned as 130 on
// SIGINT, the TTY test on fds 0 and 2, and Clikt's `UsageError` as `usage`.
package com.getlingara.cli.kotlin

import com.getlingara.cli.kotlin.canvas.Argv
import com.getlingara.cli.kotlin.canvas.Definition
import com.getlingara.cli.kotlin.canvas.Live
import com.getlingara.cli.kotlin.canvas.Part
import com.getlingara.cli.kotlin.canvas.Plan
import com.getlingara.cli.kotlin.canvas.PlanOp
import com.getlingara.cli.kotlin.canvas.Recording
import com.getlingara.cli.kotlin.canvas.Refusal
import com.getlingara.cli.kotlin.canvas.Row
import com.getlingara.cli.kotlin.canvas.Step
import com.getlingara.cli.kotlin.canvas.Stop
import com.getlingara.cli.kotlin.commands.Confirm
import com.getlingara.cli.kotlin.commands.ConfirmArgs
import com.getlingara.cli.kotlin.commands.Prompt
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.LibC
import com.getlingara.cli.kotlin.core.Signals
import com.getlingara.cli.kotlin.core.Terminal
import com.getlingara.cli.kotlin.core.Tty
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Answer
import com.getlingara.cli.kotlin.http.Classify
import com.getlingara.cli.kotlin.http.Route
import com.getlingara.cli.kotlin.http.Verdict
import com.getlingara.cli.kotlin.output.Table
import com.getlingara.cli.kotlin.store.FileStore
import com.getlingara.cli.kotlin.store.Mode
import com.getlingara.cli.kotlin.store.Stores
import sun.misc.Signal
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    // 16.10.26af AC18
    @Test
    fun core() {
        confirmRitual()
        storeSelection()
        classifierRules()
        canvasVectors()
        b1Table()
        interruptedIs130()
        ttyReadsFdsZeroAndTwo()
        usageErrorsExitTwo()
    }

    private fun ritual(terminal: Terminal, flags: ConfirmArgs): Triple<Result<String>, Int, String> {
        var reads = 0
        val script = Script()
        val ran = withCtx(app(script, terminal)) { ctx ->
            Confirm.destructive(ctx, flags) {
                reads++
                Prompt("OAuth client", "My server", "oc1", "Its tokens stop.", 2)
            }
        }
        assertTrue(script.log.isEmpty(), "the ritual itself sends nothing")
        return Triple(ran.result, reads, ran.stderr)
    }

    private fun code(r: Result<String>) = (r.exceptionOrNull() as? Fail)?.code

    /** 16.10.26ad AC3: y/n, then the typed email; `-y --confirm` replaces both; a pipe without both is refused before any call. */
    private fun confirmRitual() {
        val (ok, reads, err) = ritual(tty("y", "  Dev@Conformance.INVALID "), ConfirmArgs(false, null))
        assertEquals(EMAIL to 1, ok.getOrThrow() to reads)
        assertTrue("Delete OAuth client My server (oc1)?\nIts tokens stop.\nObjects pinning it: 2. [y/N] " in err, err)
        assertTrue("Type your developer email (d…@conformance.invalid) to confirm: " in err, err)
        for (answer in listOf("n", "", "yep")) {
            val (declined, _, stderr) = ritual(tty(answer), ConfirmArgs(false, null))
            assertEquals("confirm_declined", code(declined))
            assertEquals(Exit.NOT_CONFIRMED, (declined.exceptionOrNull() as Fail).exit)
            assertFalse("Type your developer email" in stderr)
        }
        assertEquals("confirm_email_mismatch", code(ritual(tty("y", "someone@else.invalid", EMAIL), ConfirmArgs(false, null)).first))
        for (terminal in listOf(pipe(), tty())) {
            val (flagged, flaggedReads, stderr) = ritual(terminal, ConfirmArgs(true, "DEV@conformance.invalid"))
            assertEquals(Triple(EMAIL, 0, ""), Triple(flagged.getOrThrow(), flaggedReads, stderr))
        }
        assertEquals("confirm_email_mismatch", code(ritual(pipe(), ConfirmArgs(true, "someone@else.invalid")).first))
        for (one in listOf(ConfirmArgs(true, null), ConfirmArgs(false, EMAIL), ConfirmArgs(false, null))) {
            val (refused, refusedReads, _) = ritual(pipe(), one)
            assertEquals("confirm_non_tty_refused" to 0, code(refused) to refusedReads)
        }
        assertEquals("…@example.com", Confirm.hint("d@example.com"))
    }

    /** 16.10.26ad AC6: keychain first, file fallback warned once, `LINGARA_KEYRING` obeyed, never config. */
    private fun storeSelection() {
        val dir = Files.createTempDirectory("lingara-store")
        val present = Stores(Mode.AUTO, MemoryStore(), FileStore(dir.resolve("a")))
        assertNull(present.save("default", storedRecord()))
        assertEquals(storedRecord(), present.load("default"))
        assertFalse(Files.exists(present.file.path))
        val fallback = Stores(Mode.AUTO, MemoryStore(unavailable = true), FileStore(dir.resolve("b")))
        assertTrue(fallback.save("default", storedRecord())!!.contains("LINGARA_KEYRING=keychain"))
        assertNull(fallback.save("default", storedRecord()), "warned once")
        assertEquals(storedRecord(), fallback.load("default"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fallback.file.path)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fallback.file.path.parent)))
        assertNull(Stores(Mode.FILE, MemoryStore(), FileStore(dir.resolve("c"))).save("p", storedRecord()))
        val strict = Stores(Mode.KEYCHAIN, MemoryStore(unavailable = true), FileStore(dir.resolve("d")))
        assertEquals(Exit.AUTH_REQUIRED, runCatching { strict.save("p", storedRecord()) }.exceptionOrNull().let { (it as Fail).exit })
        assertFalse(Files.exists(dir.resolve("d")), "no silent file write")
        val fixture = FileStore(dir.resolve("e")).also {
            Files.createDirectories(it.path.parent)
            Files.copy(Path.of("../conformance/fixtures/credentials/stored.json"), it.path)
        }
        assertEquals(storedRecord().copy(developerId = fixture.load("default")!!.developerId), fixture.load("default"))
        assertFalse(Files.walk(dir).anyMatch { it.fileName.toString() == "config.toml" })
    }

    private fun verdict(route: String, status: Int, body: String): Verdict {
        val (method, path) = if (route.startsWith("oauth.")) "POST" to "/oauth/x" else "GET" to "/platform/x"
        return Classify.classify(Answer(Route(route, method, path), !route.startsWith("oauth."), status, body.toByteArray(), null))
    }

    /** 16.10.26ad AC7 and B2's rules in order, rule 4a included. */
    private fun classifierRules() {
        val ends = { e: Exit -> Verdict.Ends(e) }
        assertEquals(ends(Exit.PLATFORM_DISABLED), verdict("app.list", 404, """{"code":"platform_disabled","error":"x"}"""))
        assertEquals(ends(Exit.PLATFORM_DISABLED), verdict("developer.me", 403, """{"code":"platform_access_not_enabled","error":"x"}"""))
        assertEquals(ends(Exit.PLATFORM_DISABLED), verdict("oauth.token", 400, """{"error":"invalid_grant","lingara_code":"platform_access_not_enabled"}"""))
        assertEquals(ends(Exit.AUTH_REQUIRED), verdict("oauth.token", 400, """{"error":"invalid_grant","lingara_code":"developer_account_required"}"""))
        assertEquals(ends(Exit.NOT_FOUND), verdict("app.get", 404, """{"code":"object_not_found","error":"x"}"""))
        assertEquals(ends(Exit.REFUSED), verdict("developer.me", 404, """{"code":"developer_not_found","error":"x"}"""))
        assertEquals(ends(Exit.FAILURE), verdict("app.list", 404, ""))
        assertEquals(Verdict.Refresh, verdict("app.list", 401, ""))
        assertEquals(ends(Exit.UNAVAILABLE), verdict("oauth.token", 429, """{"error":"slow"}"""))
        assertEquals(ends(Exit.FAILURE), verdict("app.list", 405, ""))
        listOf(400, 403, 409, 422).forEach { assertEquals(ends(Exit.REFUSED), verdict("app.create", it, """{"code":"c","error":"e"}""")) }
        assertEquals(ends(Exit.UNAVAILABLE), verdict("app.list", 503, "down for maintenance"))
        val held = """{"code":"dev_session_held","error":"x"}"""
        assertEquals(ends(Exit.DEV_SESSION_HELD), verdict("app_dev.session_open", 409, held))
        assertEquals(ends(Exit.DEV_SESSION_LOST), verdict("app_dev.session_renew", 409, """{"code":"dev_session_expired","error":"x"}"""))
        assertEquals(ends(Exit.REFUSED), verdict("app.create", 409, held))
    }

    private fun rendered(step: Step) = Json.obj(
        "handle" to step.handle,
        "action" to step.action,
        "records" to when (step.records) {
            Recording.RuntimeId -> "runtime_id"
            is Recording.ClientSecret -> "client_secret"
            Recording.SecretId -> "secret_id"
            Recording.Unchanged -> "unchanged"
        },
        "argv" to step.argv.map { p ->
            when (p) {
                is Part.Text -> p.text
                is Part.Id -> p.id
                is Part.Made -> "<${p.handle}>"
                is Part.Manifest -> "<${p.handle}.manifest>"
            }
        },
    )

    /** 16.10.26ad AC8: every vector in conformance/vectors/canvas-plans.json, plan and argv alike. */
    private fun canvasVectors() {
        val vectors = Json.parse(Files.readString(Path.of("../conformance/vectors/canvas-plans.json")))!!.items("vectors")
        assertTrue(vectors.size >= 8, "every rule has its vector")
        for (v in vectors) {
            val name = v.str("name")
            val def = Definition.read(v.path("definition"))
            val rows = { k: String -> v.path("live").items(k).map(Row::read) }
            val live = Live(rows("oauth_client"), rows("webhook_out"), rows("app"))
            val recorded = v.path("recorded").properties().associate { (h, r) -> h to r.str("id") }
            val kept = Plan.stillLive(def, recorded, live)
            val ops: List<PlanOp> = try {
                Plan.build(def, kept)
            } catch (r: Refusal.Script) {
                assertEquals(v.path("expect").str("refused"), r.handle, name)
                continue
            }
            assertEquals(v.path("expect").path("kept"), Json.obj(*kept.map { (h, id) -> h to id }.toTypedArray()), "$name: kept")
            assertEquals(v.path("expect").path("ops"), Json.of(ops.map(PlanOp::wire)), "$name: ops")
            if (!v.path("verdicts").isArray) continue
            try {
                assertEquals(v.path("steps"), Json.of(Argv.steps(ops, v.items("verdicts"), live, kept).map(::rendered)), "$name: steps")
            } catch (stop: Stop) {
                val want = if (stop.replace != null) Json.obj("replace" to stop.replace) else Json.obj("refused" to stop.refused.map { it.first })
                assertEquals(v.path("stop"), want, "$name: stop")
            }
        }
        assertEquals("lingara oauth_client --name 'My server'\\''s' '<n1>'", Argv.shellLine(listOf(Part.Text("oauth_client"), Part.Text("--name"), Part.Text("My server's"), Part.Made("n1"))) { "" })
    }

    /** 16.10.26ad AC9: B1's table, and its cells. */
    private fun b1Table() {
        val text = Table.render(listOf("ID", "NAME", "SERVING"), listOf(listOf("7d1e", "Flashcards", "1.0.0"), listOf("7d1e2f30-long", "Tone drills", "")))
        assertEquals("ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n", text)
        assertEquals("SCOPES  B\na       b\n", Table.render(listOf("SCOPES", "B"), listOf(listOf("a", "b"))))
        assertEquals("a:read,b:write", Table.cell(Json.of(listOf("a:read", "b:write"))))
        assertEquals("-", Table.cell(Json.of(null)))
        assertEquals("""{"a":1}""", Table.cell(Json.obj("a" to 1)))
    }

    /** D9: SIGINT inside a waiting command is polled and returned as `interrupted` (130), and the JVM lives on. */
    private fun interruptedIs130() {
        Signals.install()
        val exit = Signals.cooperative {
            Signal.raise(Signal("INT"))
            val until = System.currentTimeMillis() + 5000
            while (!Signals.stopped() && System.currentTimeMillis() < until) Thread.sleep(10)
            runCatching { Signals.sleep(10_000) }.exceptionOrNull().let { (it as Fail).exit }
        }
        Signals.reset()
        assertEquals(130, exit.code)
    }

    /** B3.1 through JNA's isatty: fds 0 and 2, both needed, never `System.console()`. */
    private fun ttyReadsFdsZeroAndTwo() {
        val asked = mutableListOf<Int>()
        val fake = { ttys: Set<Int> -> object : LibC { override fun isatty(fd: Int) = (if (fd in ttys) 1 else 0).also { asked.add(fd) } } }
        assertTrue(Tty.interactive(fake(setOf(0, 2))))
        assertEquals(listOf(0, 2), asked)
        assertFalse(Tty.interactive(fake(setOf(0, 1))))
        assertFalse(Tty.interactive(fake(setOf(1, 2))))
        val sources = Files.walk(Path.of("src/main/kotlin")).filter { it.toString().endsWith(".kt") }.toList()
        val code = sources.flatMap { Files.readAllLines(it) }.filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        assertTrue(code.none { it.contains("System.console") }, "no code line asks System.console()")
    }

    /** D9: Clikt's `UsageError` and a bare group's help-as-error both exit `usage` (2), never Clikt's 1; `--help` is 0. */
    private fun usageErrorsExitTwo() {
        val run = { argv: List<String> -> app(Script(), pipe()).run(argv, PrintStream(ByteArrayOutputStream())).exit }
        assertEquals(Exit.USAGE, run(listOf("apps", "list", "--nope")))
        assertEquals(Exit.USAGE, run(listOf("apps")))
        assertEquals(Exit.USAGE, run(listOf("nope")))
        assertEquals(Exit.USAGE, run(listOf("client", "create", "--name", "x", "--ci", "--scope", "events:read")))
        assertEquals(Exit.USAGE, run(listOf("apps", "create", "x", "--port", "0")))
        assertEquals(Exit.OK, run(listOf("--help")))
        assertEquals(Exit.OK, run(listOf("--version")))
    }
}
