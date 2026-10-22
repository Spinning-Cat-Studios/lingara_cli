// cloudflared (ADR 16.10.26ae D8). **Lookup**: `LINGARA_CLOUDFLARED` and
// then only that path, else `cloudflared` on `PATH`, refused below
// `tools.toml`'s minimum. **URL**: the CLI picks a free loopback port for
// `--metrics` and polls its `/quicktunnel`, falling back to the
// `https://…trycloudflare.com` line on stderr. **Ready**: the probe URL
// (`LINGARA_TUNNEL_PROBE_URL`, default `https://{hostname}/`) answers
// anything but Cloudflare's `530`. Each wait is 30 s.
package com.getlingara.cli.kotlin.supervise

import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Env
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Cloudflared {
    /** `tool_missing` with D8's hint, the exit row's own message. */
    fun missing() = Fail.message(Exit.TOOL_MISSING, "tool_missing_cloudflared")

    private fun onPath(name: String, env: Env): Path? {
        val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "$name.exe" else name
        return env.get("PATH")?.split(File.pathSeparatorChar)?.map { Path.of(it, exe) }?.firstOrNull { Files.isRegularFile(it) }
    }

    /** The first `a.b.c` on `cloudflared --version`'s first line. */
    private fun versionOf(binary: Path): List<Long>? = runCatching {
        val p = ProcessBuilder(binary.toString(), "--version").redirectInput(Supervise.NULL_INPUT).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val first = p.inputStream.bufferedReader().readLine().orEmpty()
        p.waitFor(10, TimeUnit.SECONDS)
        first.split(Regex("\\s+")).map { w -> w.split('.').map { it.toLongOrNull() } }.firstOrNull { v -> v.size >= 2 && v.all { it != null } }?.map { it!! }
    }.getOrNull()

    /** Field by field, as Rust compares two `Vec<u64>`. */
    private fun atLeast(v: List<Long>, min: List<Long>): Boolean {
        for (i in 0 until minOf(v.size, min.size)) if (v[i] != min[i]) return v[i] > min[i]
        return v.size >= min.size
    }

    /** Lookup and version check, offline (D7 step 2). */
    fun find(env: Env): Path {
        val configured = env.get("LINGARA_CLOUDFLARED")
        val found = if (configured != null) Path.of(configured).takeIf { Files.isRegularFile(it) } else onPath("cloudflared", env)
        val binary = found ?: throw missing()
        val version = versionOf(binary) ?: throw missing()
        if (!atLeast(version, Assets.cloudflaredMinVersion())) throw missing()
        return binary
    }

    /** The `https://…trycloudflare.com` hostname on one log line, if any. */
    fun hostnameIn(line: String): String? {
        val start = line.indexOf("https://").takeIf { it >= 0 }?.plus("https://".length) ?: return null
        val host = line.substring(start).takeWhile { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' }
        return host.takeIf { it.endsWith(".trycloudflare.com") }
    }

    fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
