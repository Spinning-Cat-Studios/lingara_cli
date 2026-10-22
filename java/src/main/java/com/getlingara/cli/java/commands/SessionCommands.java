package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.auth.Device;
import com.getlingara.cli.java.auth.Pkce;
import com.getlingara.cli.java.auth.Session;
import com.getlingara.cli.java.auth.Tokens;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.core.Table;
import com.getlingara.cli.java.http.Auth;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes.Fixed;
import com.getlingara.cli.java.http.Wire;
import com.getlingara.cli.java.store.ProfileRecord;
import java.util.List;

/**
 * {@code login}, {@code logout} and {@code whoami} (16.10.26ad D3). Under CI credentials there is
 * nothing to sign in or out of, so both exit {@code usage} with {@code login_ci_not_needed}.
 */
public final class SessionCommands {
    private SessionCommands() {}

    private static Fail ciNotNeeded() {
        return Fail.message(Exit.USAGE, "login_ci_not_needed");
    }

    public static void login(Ctx ctx, boolean device) {
        if (Calls.ciPair(ctx) != null) {
            throw ciNotNeeded();
        }
        JsonNode tokens = device ? Device.login(ctx) : Pkce.login(ctx);
        ProfileRecord record = new ProfileRecord();
        Tokens.read(tokens).apply(record, ctx.app.http.clock().now());
        // The new token, for this one read: whose is it?
        ctx.app.session = Session.stored(record.copy(), null);
        JsonNode me = Calls.call(ctx, Request.of(Fixed.DEVELOPER_ME.route()));
        record.developerId = Json.text(me, "developer_id", "");
        record.email = Json.text(me, "email", "");
        Calls.saveRecord(ctx, record);
        ctx.out.note(Assets.message("login_signed_in", "email", record.email));
        ctx.out.nothing();
    }

    /**
     * Revokes the refresh token (RFC 7009) and forgets the profile. A revoke that fails, the
     * network being down say, still forgets it and warns.
     */
    public static void logout(Ctx ctx) {
        if (Calls.ciPair(ctx) != null) {
            throw ciNotNeeded();
        }
        ProfileRecord record = ctx.app.stores.load(ctx.profile);
        if (record == null) {
            throw Fail.auth("not signed in: nothing to sign out of");
        }
        List<Pair> form = List.of(
                new Pair("token", record.refreshToken),
                new Pair("token_type_hint", "refresh_token"),
                new Pair("client_id", ctx.app.endpoints.clientId()));
        String why = null;
        try {
            Wire.In revoked = ctx.app.http.send(Request.of(Fixed.REVOKE.route()).withForm(form), Auth.bearer(record.accessToken));
            if (revoked.status() < 200 || revoked.status() >= 300) {
                why = "HTTP " + revoked.status();
            }
        } catch (Fail fail) {
            why = fail.text();
        }
        if (why != null) {
            ctx.out.note("warning: the refresh token could not be revoked (" + why + "); forgetting it here anyway");
        }
        ctx.app.stores.delete(ctx.profile);
        ctx.app.session = null;
        ctx.out.nothing();
    }

    /** {@code EMAIL  DEVELOPER  SCOPES  PROFILE}, {@code SCOPES} from the token response. */
    public static void whoami(Ctx ctx) {
        JsonNode me = Calls.call(ctx, Request.of(Fixed.DEVELOPER_ME.route()));
        if (ctx.out.json()) {
            ctx.out.jsonValue(me);
            return;
        }
        String scopes = ctx.app.session == null ? "" : ctx.app.session.scopes();
        List<String> row = List.of(Table.cell(me.path("email")), Table.cell(me.path("developer_id")), scopes, ctx.profile);
        ctx.out.print(Table.render(List.of("EMAIL", "DEVELOPER", "SCOPES", "PROFILE"), List.of(row)));
    }
}
