// The config dir and `config.toml` (16.10.26ad D4). The dir is
// `LINGARA_CONFIG_DIR`, else `$XDG_CONFIG_HOME/lingara`, else
// `~/.config/lingara` on macOS and Linux alike (never `~/Library/Application
// Support`), and `%APPDATA%\lingara` on Windows. `config.toml` holds only
// non-secret per-profile settings: `[profiles.<name>]` with `api_url` and
// `output`.
package com.getlingara.cli.kotlin.store

import com.getlingara.cli.kotlin.core.Env
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path

/** One profile's settings: the third source of D3's API order, and `json` as the default output. */
data class ProfileConfig(val apiUrl: String? = null, val output: String? = null)

object Config {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun dir(env: Env): Path? {
        env.get("LINGARA_CONFIG_DIR")?.let { return Path.of(it) }
        if (windows) return env.get("APPDATA")?.let { Path.of(it, "lingara") }
        env.get("XDG_CONFIG_HOME")?.let { return Path.of(it, "lingara") }
        val home = env.get("HOME") ?: System.getProperty("user.home") ?: return null
        return Path.of(home, ".config", "lingara")
    }

    /** The profile's settings; a missing or unreadable file is no settings. */
    fun profile(dir: Path?, profile: String): ProfileConfig {
        val file = dir?.resolve("config.toml") ?: return ProfileConfig()
        val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return ProfileConfig()
        val parsed = Toml.parse(text)
        if (parsed.hasErrors()) return ProfileConfig()
        val table = parsed.getTable("profiles")?.getTable(listOf(profile)) ?: return ProfileConfig()
        return ProfileConfig(table.getString("api_url"), table.getString("output"))
    }
}
