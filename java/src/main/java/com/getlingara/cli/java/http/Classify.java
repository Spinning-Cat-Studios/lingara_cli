package com.getlingara.cli.java.http;

import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one response classifier (ADR 16.10.26ad D8; contract B2). Its rules are <strong>data</strong>:
 * an ordered list of (predicate, verdict) entries, the first match winning and rule 8 the
 * catch-all. 16.10.26ae D10's rule 4a is one more entry, after rule 4. A verdict of {@code null}
 * is rule 2's: refresh once, then replay once.
 */
public final class Classify {
    /** {@code conformance/routes.toml}'s two codes, as this kit keys them. */
    public static final String PLATFORM_DISABLED_CODE = "platform_disabled";
    public static final String PLATFORM_ACCESS_CODE = "platform_access_not_enabled";

    private Classify() {}

    private record Rule(Predicate<Answer> matches, Function<Answer, Exit> verdict) {}

    /** Rule 4a: a dev-session 409, keyed on (route, body code) (16.10.26ae D10). */
    private record DevRow(String route, String code, Exit exit) {}

    private static final List<DevRow> DEV_SESSION_CODES = List.of(
            new DevRow("app_dev.session_open", "dev_session_held", Exit.DEV_SESSION_HELD),
            new DevRow("app_dev.patch", "dev_session_held", Exit.DEV_SESSION_HELD),
            new DevRow("app_dev.session_renew", "dev_session_not_live", Exit.DEV_SESSION_LOST),
            new DevRow("app_dev.session_renew", "dev_session_expired", Exit.DEV_SESSION_LOST));

    private static final List<Rule> RULES = List.of(
            new Rule(Classify::platformDisabled, a -> Exit.PLATFORM_DISABLED),
            new Rule(a -> a.status() == 401 && a.bearer() && !a.token(), a -> null),
            new Rule(Classify::oauthRefusal, a -> Exit.AUTH_REQUIRED),
            new Rule(a -> a.status() == 404 && a.coded(), Classify::codedNotFound),
            new Rule(a -> devSessionRow(a) != null, Classify::devSessionRow),
            new Rule(a -> a.status() == 404 && a.emptyBody(), a -> Exit.FAILURE),
            new Rule(a -> List.of(400, 403, 409, 422).contains(a.status()), a -> Exit.REFUSED),
            new Rule(a -> a.status() == 429 || a.status() >= 500, a -> Exit.UNAVAILABLE),
            new Rule(a -> true, a -> Exit.FAILURE));

    /** The rule's exit, or {@code null} for rule 2's refresh. */
    public static Exit classify(Answer answer) {
        for (Rule rule : RULES) {
            if (rule.matches().test(answer)) {
                return rule.verdict().apply(answer);
            }
        }
        return Exit.FAILURE;
    }

    private static boolean platformDisabled(Answer a) {
        String code = a.member("code");
        boolean dark = a.status() == 404 && a.platform() && PLATFORM_DISABLED_CODE.equals(code);
        boolean door = a.status() == 403 && PLATFORM_ACCESS_CODE.equals(code);
        boolean token = a.token() && PLATFORM_ACCESS_CODE.equals(a.member("lingara_code"));
        return dark || door || token;
    }

    private static boolean oauthRefusal(Answer a) {
        return a.token() && a.status() >= 400 && a.status() < 500 && a.status() != 429 && a.oauthEnvelope();
    }

    /** Rule 4's one exception is keyed on route <strong>and</strong> code. */
    private static Exit codedNotFound(Answer a) {
        boolean developerGone = a.route().equals("developer.me") && "developer_not_found".equals(a.member("code"));
        return developerGone ? Exit.REFUSED : Exit.NOT_FOUND;
    }

    private static Exit devSessionRow(Answer a) {
        String code = a.member("code");
        if (a.status() != 409 || code == null) {
            return null;
        }
        for (DevRow row : DEV_SESSION_CODES) {
            if (row.route().equals(a.route()) && row.code().equals(code)) {
                return row.exit();
            }
        }
        return null;
    }

    /**
     * The refusal B2 prints for {@code exit}: the server's own code and text where it sent them,
     * the kit's sentence where it did not.
     */
    public static Fail failFor(Answer a, Exit exit) {
        Fail fail = switch (exit) {
            case PLATFORM_DISABLED -> new Fail(exit, PLATFORM_DISABLED_CODE, Assets.message("platform_disabled"));
            case FAILURE -> new Fail(Exit.FAILURE, "unexpected_response", "unexpected response from " + a.route());
            case AUTH_REQUIRED -> new Fail(exit, or(a.member("error"), "auth_required"),
                    or(a.member("error_description"), a.route() + " refused the credentials"));
            default -> new Fail(exit, or(a.member("code"), exit == Exit.UNAVAILABLE ? "unavailable" : "refused"),
                    or(a.member("error"), a.route() + " answered HTTP " + a.status()));
        };
        Long secs = a.retryAfter();
        if (secs != null && exit == Exit.UNAVAILABLE) {
            fail.text(fail.text() + " (retry after " + secs + " s)");
        }
        // A command that re-words a refusal (apps dev's dev_session_held) reads the body here.
        fail.body(new String(a.reply().body(), StandardCharsets.UTF_8));
        return fail;
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
