// `lingara`, the Lingara developer CLI: the Kotlin kit (ADR 16.10.26af). Every
// behaviour a developer or CI job can observe is `conformance/CONTRACT.md`'s;
// this package is one way of keeping it.
//
// Both streams are written as UTF-8 bytes whatever the JVM's default charset:
// JDK 17 takes it from the locale, and a conformance child runs with none,
// where `→`, `—` and `│` would print as `?`.
package com.getlingara.cli.kotlin

import com.getlingara.cli.kotlin.app.App
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Env
import com.getlingara.cli.kotlin.core.Signals
import com.getlingara.cli.kotlin.core.Stdio
import com.getlingara.cli.kotlin.http.Bases
import com.getlingara.cli.kotlin.http.Http
import com.getlingara.cli.kotlin.http.Identity
import com.getlingara.cli.kotlin.http.JdkTransport
import com.getlingara.cli.kotlin.http.SystemClock
import com.getlingara.cli.kotlin.store.Config
import com.getlingara.cli.kotlin.store.FileStore
import com.getlingara.cli.kotlin.store.Keychain
import com.getlingara.cli.kotlin.store.Mode
import com.getlingara.cli.kotlin.store.Stores
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/** `macos`, `linux` or `windows`, as the Rust kit's `std::env::consts::OS`. */
private fun os(): String {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        name.startsWith("mac") -> "macos"
        name.startsWith("windows") -> "windows"
        else -> name.replace(Regex("[^a-z0-9_]"), "_")
    }
}

/** `LC_ALL`, else `LC_MESSAGES`, else `LANG`, as BCP 47: `en_US.UTF-8` is `en-US`; `C` and `POSIX` name no language. */
fun acceptLanguage(env: Env): String? {
    val raw = env.get("LC_ALL") ?: env.get("LC_MESSAGES") ?: env.get("LANG") ?: return null
    val tag = raw.split('.', '@').first().replace('_', '-')
    return tag.takeUnless { it.isEmpty() || it == "C" || it == "POSIX" }
}

/** Opens [url] with the platform's opener; `false` when it could not. */
private fun openBrowser(url: String): Boolean {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        name.startsWith("mac") -> listOf("open", url)
        name.startsWith("windows") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }
    return runCatching {
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }.isSuccess
}

fun productionApp(env: Env): App {
    val endpoints = Assets.endpoints
    val configDir = Config.dir(env)
    val stores = Stores(Mode.fromEnv(env.get("LINGARA_KEYRING")), Keychain(), FileStore(configDir ?: Path.of(".")))
    val portal = env.get("LINGARA_PLATFORM_URL") ?: endpoints.platformUrl
    val identity = Identity("lingara-cli/${Assets.version} (kotlin; ${os()})", acceptLanguage(env))
    val http = Http(JdkTransport(), SystemClock, Bases(endpoints.apiUrl, portal), identity)
    val noBrowser = env.get("LINGARA_NO_BROWSER") == "1"
    return App(http, env, stores, Stdio(), { url -> !noBrowser && openBrowser(url) }, endpoints, configDir)
}

fun main(args: Array<String>) {
    Signals.install()
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    val outcome = productionApp(Env.fromProcess()).run(args.toList(), err)
    val out = FileOutputStream(FileDescriptor.out)
    runCatching { out.write(outcome.stdout.toByteArray(Charsets.UTF_8)); out.flush() }
    err.flush()
    exitProcess(outcome.exit.code)
}
