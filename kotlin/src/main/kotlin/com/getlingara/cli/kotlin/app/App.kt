// One run: parse argv, pick the profile and the API, dispatch, and turn the
// result into an exit code. [App.run] is the one entry point, used by `main`
// and, in-process, by canvas apply's inner verbs (16.10.26ad D7 step 4),
// which share this app's signed-in session, so no second token exchange
// happens.
package com.getlingara.cli.kotlin.app

import com.getlingara.cli.kotlin.auth.SessionState
import com.getlingara.cli.kotlin.cli.Parse
import com.getlingara.cli.kotlin.cli.Parsed
import com.getlingara.cli.kotlin.core.Endpoints
import com.getlingara.cli.kotlin.core.Env
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Terminal
import com.getlingara.cli.kotlin.http.Http
import com.getlingara.cli.kotlin.output.Errors
import com.getlingara.cli.kotlin.output.Output
import com.getlingara.cli.kotlin.store.Config
import com.getlingara.cli.kotlin.store.Stores
import java.io.PrintStream
import java.nio.file.Path

/** What one run ended with: its exit, its stdout, and the refusal, if any. */
class Outcome(val exit: Exit, val stdout: String, val fail: Fail? = null)

/** Every seam a run has, faked once in the unit tests. */
class App(
    val http: Http,
    val env: Env,
    val stores: Stores,
    val terminal: Terminal,
    /** Opens a URL in the browser; `false` when it could not. */
    val browser: (String) -> Boolean,
    val endpoints: Endpoints,
    val configDir: Path?,
) {
    var session: SessionState? = null

    /** Runs [argv] (without the binary's name), writing stderr to [err]. */
    fun run(argv: List<String>, err: PrintStream): Outcome {
        val json = "--json" in argv
        val parsed = try {
            Parse.parse(argv)
        } catch (fail: Fail) {
            return finish(Output(err, json), fail)
        }
        if (parsed is Parsed.Printed) return Outcome(Exit.OK, parsed.text)
        parsed as Parsed.Chosen
        val profile = parsed.profile ?: env.get("LINGARA_PROFILE")
        val config = Config.profile(configDir, profile ?: "default")
        http.bases.api = apiBase(config.apiUrl)
        val out = Output(err, parsed.json || config.output == "json")
        val ctx = Ctx(this, out, profile ?: "default", profile != null)
        return try {
            parsed.leaf.execute(ctx)
            Outcome(Exit.OK, out.stdout.toString())
        } catch (fail: Fail) {
            finish(out, fail)
        } catch (e: RuntimeException) {
            // A bug, or a response the kit did not expect: B2's `failure`, never a stack trace.
            finish(out, Fail.failure(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * D3's order: `LINGARA_API_URL`, then `platform.toml`'s `[platform]
     * api`, then the profile's `api_url`, then `endpoints.json`'s. The `apps`
     * commands that read a `platform.toml` apply its slot when they open the
     * file (16.10.26ae D4, `apps.Rows`); everything else never reads one.
     */
    private fun apiBase(profileApi: String?): String = env.get("LINGARA_API_URL") ?: profileApi ?: endpoints.apiUrl

    private fun finish(out: Output, fail: Fail): Outcome {
        Errors.lines(fail, out.json).forEach(out::note)
        return Outcome(fail.exit, out.stdout.toString(), fail)
    }
}
