package com.getlingara.cli.java.commands;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.Mixins.ConfirmFlags;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant I1). One function, called
 * by every dangerous command, so no command can carry a weaker copy.
 *
 * <ul>
 *   <li>{@code -y --confirm <email>} replaces both prompts, interactive or not, and sends no read
 *       for a prompt; the email is checked before anything is destroyed. Neither flag alone is
 *       confirmation.
 *   <li>Otherwise a non-interactive run is refused <strong>before any request</strong>, the read
 *       and any token exchange included.
 *   <li>Interactive: the command's read fills the prompt, {@code y}/{@code yes} continues, then
 *       the typed email must match. There is no retry loop.
 * </ul>
 */
public final class Confirm {
    private Confirm() {}

    /** What the y/n question names: the object, its cascade, its pins. */
    public record Prompt(String kind, String name, String id, String consequence, long pinnedBy) {}

    /** Trimmed and ASCII-lowercased: the comparison's rule and the form the request carries. */
    public static String canonical(String email) {
        StringBuilder out = new StringBuilder();
        // ASCII case-folding only (B3.2), so a non-ASCII letter compares as typed.
        email.strip().chars().forEach(c -> out.append((char) (c >= 'A' && c <= 'Z' ? c + 32 : c)));
        return out.toString();
    }

    /**
     * {@code dev@example.com} is {@code d…@example.com}; a one-character local part is {@code
     * …@example.com}. A typo stays catchable without printing the address.
     */
    public static String hint(String email) {
        int at = email.indexOf('@');
        String local = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? "" : email.substring(at + 1);
        String first = local.codePointCount(0, local.length()) > 1 ? new String(Character.toChars(local.codePointAt(0))) : "";
        return first + "…@" + domain;
    }

    private static Fail notConfirmed(String id) {
        return new Fail(Exit.NOT_CONFIRMED, id, Assets.message(id));
    }

    /** The ritual; the canonical email the destructive request then carries. */
    public static String destructive(Ctx ctx, ConfirmFlags flags, Function<Ctx, Prompt> read) {
        if (flags.yes && flags.confirm != null) {
            String email = canonical(Calls.email(ctx));
            if (canonical(flags.confirm).equals(email)) {
                return email;
            }
            throw notConfirmed("confirm_email_mismatch");
        }
        if (!ctx.app.terminal.interactive()) {
            throw notConfirmed("confirm_non_tty_refused");
        }
        Prompt prompt = read.apply(ctx);
        ctx.out.prompt(question(prompt) + " [y/N] ");
        String answer = canonical(orEmpty(ctx.app.terminal.readLine()));
        if (!answer.equals("y") && !answer.equals("yes")) {
            throw notConfirmed("confirm_declined");
        }
        String email = canonical(Calls.email(ctx));
        ctx.out.prompt(Assets.message("confirm_type_email", "hint", hint(email)) + " ");
        if (canonical(orEmpty(ctx.app.terminal.readLine())).equals(email)) {
            return email;
        }
        throw notConfirmed("confirm_email_mismatch");
    }

    private static String orEmpty(String line) {
        return line == null ? "" : line;
    }

    /** {@code confirm_yes_no}, the consequence, and {@code confirm_pinned_by} when pinned. */
    private static String question(Prompt p) {
        List<String> lines = new ArrayList<>();
        lines.add(Assets.message("confirm_yes_no", "kind", p.kind(), "name", p.name(), "id", p.id()));
        lines.add(p.consequence());
        if (p.pinnedBy() > 0) {
            lines.add(Assets.message("confirm_pinned_by", "count", Long.toString(p.pinnedBy())));
        }
        return String.join("\n", lines);
    }
}
