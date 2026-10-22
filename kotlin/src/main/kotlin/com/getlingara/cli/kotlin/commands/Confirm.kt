// The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant I1).
// One function, called by every dangerous command, so no command can carry a
// weaker copy.
//
// - `-y --confirm <email>` replaces both prompts, interactive or not, and
//   sends no read for a prompt; the email is checked before anything is
//   destroyed. Neither flag alone is confirmation.
// - Otherwise a non-interactive run is refused **before any request**, the
//   read and any token exchange included.
// - Interactive: the command's read fills the prompt, `y`/`yes` continues,
//   then the typed email must match. There is no retry loop.
package com.getlingara.cli.kotlin.commands

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.email
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail

/** What the y/n question names: the object, its cascade, its pins. */
data class Prompt(val kind: String, val name: String, val id: String, val consequence: String, val pinnedBy: Long)

/** The confirm group's two flags, as parsed. */
data class ConfirmArgs(val yes: Boolean, val email: String?)

object Confirm {
    /** Trimmed and ASCII-lowercased: the comparison's rule and the form the destructive request carries. */
    fun canonical(email: String): String = email.trim().map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

    /** `dev@example.com` is `d…@example.com`; a one-character local part is `…@example.com`. */
    fun hint(email: String): String {
        val local = email.substringBefore('@')
        val domain = if ('@' in email) email.substringAfter('@') else ""
        val first = if (local.codePointCount(0, local.length) > 1) String(Character.toChars(local.codePointAt(0))) else ""
        return "$first…@$domain"
    }

    private fun notConfirmed(id: String) = Fail(Exit.NOT_CONFIRMED, id, Assets.message(id))

    /** The ritual; the canonical email the destructive request then carries. */
    fun destructive(ctx: Ctx, flags: ConfirmArgs, read: (Ctx) -> Prompt): String {
        if (flags.yes && flags.email != null) {
            val email = canonical(ctx.email())
            return if (canonical(flags.email) == email) email else throw notConfirmed("confirm_email_mismatch")
        }
        if (!ctx.app.terminal.interactive()) throw notConfirmed("confirm_non_tty_refused")
        val prompt = read(ctx)
        ctx.out.prompt("${question(prompt)} [y/N] ")
        val answer = ctx.app.terminal.readLine().orEmpty().trim().lowercase()
        if (answer != "y" && answer != "yes") throw notConfirmed("confirm_declined")
        val email = canonical(ctx.email())
        ctx.out.prompt(Assets.message("confirm_type_email", "hint" to hint(email)) + " ")
        val typed = ctx.app.terminal.readLine().orEmpty()
        return if (canonical(typed) == email) email else throw notConfirmed("confirm_email_mismatch")
    }

    /** `confirm_yes_no`, the consequence, and `confirm_pinned_by` when pinned. */
    private fun question(p: Prompt): String {
        val lines = mutableListOf(Assets.message("confirm_yes_no", "kind" to p.kind, "name" to p.name, "id" to p.id), p.consequence)
        if (p.pinnedBy > 0) lines.add(Assets.message("confirm_pinned_by", "count" to p.pinnedBy.toString()))
        return lines.joinToString("\n")
    }
}
