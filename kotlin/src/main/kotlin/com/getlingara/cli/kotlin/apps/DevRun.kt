// An `apps dev` session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it, start
// the app with the session's credentials, hand over to `DevHold`, then end
// in order — `DELETE` the session, stop cloudflared, stop the app's tree —
// each step running even if the one before it failed. Ctrl+C and SIGTERM are
// polled, never fatal, so the order runs and the exit is `interrupted` (130)
// returned (ADR 16.10.26af D9). Under `--json`, stdout carries one value,
// written here at exit.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Signals
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.supervise.Group
import com.getlingara.cli.kotlin.supervise.Supervise
import com.getlingara.cli.kotlin.supervise.Tunnel
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

private const val APP_PREFIX = "app │ "
private val LISTEN_WAIT: Duration = Duration.ofSeconds(60)
private val STOP_GRACE: Duration = Duration.ofSeconds(5)

/** The children and what the session pointed at, for step 8 and the report. */
class Run {
    var app: Group? = null
    var tunnel: Tunnel? = null
    val urls = mutableListOf<String>()

    /** The session ended under us (taken over, expired): no `DELETE`. */
    var lost = false
    var respawns = 0

    fun stopChildren() {
        tunnel?.stop()
        app?.stop(STOP_GRACE)
    }
}

object DevRun {
    fun lost(name: String) = Fail.message(Exit.DEV_SESSION_LOST, "dev_session_lost", "name" to name)

    /** `409 dev_session_held` re-worded with the app's name and the lease. */
    private fun held(fail: Fail, name: String): Fail {
        if (fail.exit != Exit.DEV_SESSION_HELD) return fail
        val lease = fail.body?.let { Json.parse(it) }?.strOrNull("lease_expires_at") ?: "its lease ends"
        return Fail.message(Exit.DEV_SESSION_HELD, "dev_session_held", "name" to name, "lease_expires_at" to lease)
    }

    fun session(ctx: Ctx, plan: DevPlan) = Signals.cooperative {
        val clientId = if (plan.client) Objects.get(ctx, Kind.APP, plan.id).strOrNull("client_id") else null
        val session = try {
            DevApi.open(ctx, plan.id, plan.takeOver, plan.client)
        } catch (fail: Fail) {
            throw held(fail, plan.name)
        }
        val run = Run()
        val failure = try {
            live(ctx, plan, session, clientId, run)
            null
        } catch (fail: Fail) {
            fail
        }
        if (!run.lost) runCatching { DevApi.end(ctx, plan.id, session.id) }
        run.stopChildren()
        if (ctx.out.json) {
            val exit = failure?.exit ?: Exit.OK
            ctx.out.jsonValue(Json.obj("name" to plan.name, "app_id" to plan.id, "session_id" to session.id, "urls" to run.urls, "exit" to exit.word))
        }
        failure?.let { throw it }
    }

    private fun live(ctx: Ctx, plan: DevPlan, session: DevSession, clientId: String?, run: Run) {
        val extra = sessionVars(plan, session, clientId)
        announce(ctx, plan, session, extra)
        run.app = startApp(plan, Supervise.childEnv(ctx.app.env.vars, extra))
        if (!waitApp(plan, run)) return
        DevHold.firstUrl(ctx, plan, session, run)
        DevHold.hold(ctx, plan, session, run)
    }

    /** What the app's environment gains (D5, D7 step 4): the row's `env` and `env_file`, the session's credentials, and the port. */
    private fun sessionVars(plan: DevPlan, session: DevSession, clientId: String?): List<Pair<String, String>> {
        val vars = plan.env.toMutableList()
        vars.add("LINGARA_APP_SECRET" to session.appSecret)
        if (clientId != null && session.clientSecret != null) {
            vars.add("LINGARA_CLIENT_ID" to clientId)
            vars.add("LINGARA_CLIENT_SECRET" to session.clientSecret)
        }
        vars.add("PORT" to plan.port.toString())
        vars.add("LINGARA_APP_PORT" to plan.port.toString())
        return vars
    }

    /** `--show-env` (names only) and `--write-env` (mode 0600), both on stderr. */
    private fun announce(ctx: Ctx, plan: DevPlan, session: DevSession, vars: List<Pair<String, String>>) {
        if (plan.showEnv) vars.forEach { (name, _) -> ctx.out.note("env: $name") }
        val path = plan.writeEnv ?: return
        writePrivate(path, vars.joinToString("") { (k, v) -> "$k=$v\n" })
        ctx.out.note(Assets.message("apps_dev_env_written", "path" to path.toString(), "expires_at" to session.expiresAt))
    }

    private fun writePrivate(path: Path, text: String) {
        try {
            Files.deleteIfExists(path)
            val attrs = runCatching { arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }.getOrDefault(emptyArray())
            runCatching { Files.createFile(path, *attrs) }.recoverCatching { Files.createFile(path) }
            Files.writeString(path, text, Charsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (e: IOException) {
            throw Fail.failure("$path: ${e.message}")
        }
    }

    private fun startApp(plan: DevPlan, env: Map<String, String>): Group {
        val builder = Supervise.shell(plan.start).redirectInput(Supervise.NULL_INPUT)
        val app = try {
            Supervise.start(builder, plan.dir, env)
        } catch (e: IOException) {
            throw Fail.failure("${plan.start}: ${e.message}")
        }
        val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
        Supervise.relay(app.process.inputStream, APP_PREFIX, err)
        Supervise.relay(app.process.errorStream, APP_PREFIX, err)
        return app
    }

    /** The app's own exit: `ok` on status 0, else `app_exited`. */
    fun appResult(plan: DevPlan, status: Int) {
        if (status != 0) throw Fail.message(Exit.APP_EXITED, "app_exited", "name" to plan.name, "status" to status.toString())
    }

    /** Step 4's wait: the port accepts within 60 s. `false` when the app already ended the session by exiting 0. */
    private fun waitApp(plan: DevPlan, run: Run): Boolean {
        val app = run.app ?: return false
        var exited: Int? = null
        val listening = Supervise.waitListening(plan.port, LISTEN_WAIT) {
            exited = exited ?: app.exited()
            Signals.stopped() || exited != null
        }
        Signals.check()
        exited?.let {
            appResult(plan, it)
            return false
        }
        if (!listening) throw Fail.message(Exit.APP_NOT_LISTENING, "app_not_listening", "name" to plan.name, "port" to plan.port.toString())
        return true
    }
}
