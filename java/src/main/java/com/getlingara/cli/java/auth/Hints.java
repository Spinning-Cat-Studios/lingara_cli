package com.getlingara.cli.java.auth;

import com.getlingara.cli.java.core.Fail;
import java.util.Map;

/**
 * The one line of advice a refusal's code earns (16.10.26ad D3, D5, D6). Keyed by code, so a
 * reworded server sentence changes nothing.
 */
public final class Hints {
    public static final String PORTAL_LINE = "create your developer account at https://platform.getlingara.com";

    private static final Map<String, String> BY_CODE = Map.of(
            "developer_not_found", PORTAL_LINE,
            "developer_account_required", PORTAL_LINE,
            "developer_terms_outdated", "accept the updated developer terms at https://platform.getlingara.com",
            "delete_confirmation_mismatch", "the platform holds a different email for you; run `lingara whoami`",
            "invalid_scope", "recreate this client with `lingara client create --ci`");

    private Hints() {}

    /** {@code fail} with its code's line, once. */
    public static Fail advise(Fail fail) {
        String line = BY_CODE.get(fail.code());
        if (line != null && !fail.hints().contains(line)) {
            fail.hint(line);
        }
        return fail;
    }
}
