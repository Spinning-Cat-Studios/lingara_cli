// The children `apps dev` runs (ADR 16.10.26ae D5, D7 steps 4 and 8; ADR
// 16.10.26af D2): the app's environment, built from the parent's with the
// CLI's own platform credentials scrubbed; the platform shell (`sh -c`,
// `cmd /C`); and a stop that reaches the whole tree. A JVM cannot start a
// child in a process group of its own, so the tree is
// `ProcessHandle.descendants()`, read before the first signal: SIGTERM to
// every member, then SIGKILL to whatever is left once the grace period ends.
package com.getlingara.cli.kotlin.supervise

import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.time.Duration

/** The CLI's own credentials, never the app's (D5): `LINGARA_PLATFORM_*`. */
const val SCRUBBED_PREFIX = "LINGARA_PLATFORM_"

private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")

object Supervise {
    /** [line] through the platform shell, exactly as the developer would type it. */
    fun shell(line: String): ProcessBuilder = if (WINDOWS) ProcessBuilder("cmd", "/C", line) else ProcessBuilder("sh", "-c", line)

    /** The null device, for a child's stdin. */
    val NULL_INPUT: ProcessBuilder.Redirect = ProcessBuilder.Redirect.from(File(if (WINDOWS) "NUL" else "/dev/null"))

    /** The parent's environment, minus the CLI's credentials, plus [extra] (later entries win). */
    fun childEnv(parent: Map<String, String>, extra: List<Pair<String, String>>): Map<String, String> =
        parent.filterKeys { !it.startsWith(SCRUBBED_PREFIX) }.toSortedMap() + extra

    /** Starts [builder] in [dir] with exactly [env]. */
    fun start(builder: ProcessBuilder, dir: Path?, env: Map<String, String>?): Group {
        dir?.let { builder.directory(it.toFile()) }
        if (env != null) {
            builder.environment().clear()
            builder.environment().putAll(env)
        }
        return Group(builder.start())
    }

    /** Copies [source] to [sink] line by line, each line after [prefix], on a daemon thread. */
    fun relay(source: InputStream, prefix: String, sink: PrintStream) {
        val thread = Thread {
            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line -> synchronized(sink) { sink.print("$prefix$line\n"); sink.flush() } }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** Whether `127.0.0.1:[port]` accepts a connection before [timeout], giving up early when [stop] says so. */
    fun waitListening(port: Int, timeout: Duration, stop: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) } }.isSuccess
            if (up) return true
            if (stop()) return false
            Thread.sleep(100)
        }
        return false
    }
}

/** A running child and the tree under it. */
class Group(val process: Process) {
    /** The exit status, if it has exited. */
    fun exited(): Int? = if (process.isAlive) null else process.exitValue()

    /** SIGTERM to the child and every descendant, then SIGKILL to what is left once [grace] passes. */
    fun stop(grace: Duration) {
        val tree = listOf(process.toHandle()) + process.descendants().toList()
        tree.forEach { it.destroy() }
        val deadline = System.nanoTime() + grace.toNanos()
        while (tree.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(50)
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
        runCatching { process.waitFor() }
    }
}
