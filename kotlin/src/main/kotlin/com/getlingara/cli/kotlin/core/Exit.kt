// The exit codes (16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10, rows
// 9–18) and the one refusal every command throws, which `output.Errors`
// prints as contract B2's line.
package com.getlingara.cli.kotlin.core

/** A row of `spec/cli.schema.json`'s exit table: its code and its name. */
enum class Exit(val code: Int, val word: String) {
    OK(0, "ok"),
    FAILURE(1, "failure"),
    USAGE(2, "usage"),
    AUTH_REQUIRED(3, "auth_required"),
    NOT_FOUND(4, "not_found"),
    REFUSED(5, "refused"),
    NOT_CONFIRMED(6, "not_confirmed"),
    UNAVAILABLE(7, "unavailable"),
    PLATFORM_DISABLED(8, "platform_disabled"),
    TOOL_MISSING(9, "tool_missing"),
    TUNNEL_FAILED(10, "tunnel_failed"),
    DEV_SESSION_HELD(11, "dev_session_held"),
    DEV_SESSION_LOST(12, "dev_session_lost"),
    APP_EXITED(13, "app_exited"),
    CONNECTION_SCRIPTED(14, "connection_scripted"),
    RENDER_URL_EPHEMERAL(15, "render_url_ephemeral"),
    PLATFORM_TOML_SECRET(16, "platform_toml_secret"),
    PLATFORM_TOML_SCHEMA(17, "platform_toml_schema"),
    APP_NOT_LISTENING(18, "app_not_listening"),
    INTERRUPTED(130, "interrupted"),
}

/** An object a create made before it failed, named on stderr (16.10.26ad D2). */
data class Made(val kind: String, val id: String)

/**
 * Why a command stopped: its exit, the code and text B2 prints, any extra
 * lines of advice, and what a part-way create already made. Thrown, so a
 * handler reads as the plain sequence of calls it makes.
 */
class Fail(val exit: Exit, val code: String, var text: String) : RuntimeException(text) {
    val hints: MutableList<String> = mutableListOf()
    var made: List<Made> = emptyList()

    /** The refusing answer's body, for a command that re-words it. */
    var body: String? = null

    override val message: String get() = text

    fun hint(line: String): Fail = apply { hints.add(line) }

    companion object {
        fun usage(text: String) = Fail(Exit.USAGE, "usage", text)

        fun auth(text: String) = Fail(Exit.AUTH_REQUIRED, "auth_required", text)

        fun failure(text: String) = Fail(Exit.FAILURE, "failure", text)

        /** A refusal with a `messages.en.json` text: code and id are one word, as every 16.10.26ae row's are. */
        fun message(exit: Exit, id: String, vararg vars: Pair<String, String>) = Fail(exit, id, Assets.message(id, *vars))

        fun interrupted() = Fail(Exit.INTERRUPTED, "interrupted", "interrupted")
    }
}
