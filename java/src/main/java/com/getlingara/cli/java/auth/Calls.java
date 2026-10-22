package com.getlingara.cli.java.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.core.Rfc3339;
import com.getlingara.cli.java.http.Answer;
import com.getlingara.cli.java.http.Auth;
import com.getlingara.cli.java.http.Classify;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Route;
import com.getlingara.cli.java.http.Routes.Fixed;
import com.getlingara.cli.java.http.Wire;
import com.getlingara.cli.java.store.ProfileRecord;
import java.util.List;

/**
 * Every authenticated call (16.10.26ad D3, contract B2 rule 2): a {@code 401} to a bearer
 * request refreshes once (a fresh {@code client_credentials} mint under CI) and replays once; a
 * second {@code 401} is {@code auth_required}, and any other answer to the replay is classified
 * again. The session opens on first use: CI client credentials, a stored credential from {@code
 * login}, or neither, in that order.
 */
public final class Calls {
    private Calls() {}

    /** A 2xx body as JSON; an empty one is {@code {}}. */
    static JsonNode bodyJson(Wire.In reply, Route route) {
        if (new Answer(route.name(), false, false, false, reply).emptyBody()) {
            return Json.object();
        }
        JsonNode body = Json.parse(reply.body());
        if (body == null) {
            throw Fail.failure("unexpected response from " + route.name());
        }
        return body;
    }

    private static boolean ok(Wire.In reply) {
        return reply.status() >= 200 && reply.status() < 300;
    }

    /** The refusal for a non-2xx reply that is not rule 2's. */
    public static Fail refusal(Route route, Wire.In reply, boolean bearer) {
        Answer a = Answer.of(route, reply, bearer);
        Exit exit = Classify.classify(a);
        Fail fail = Hints.advise(Classify.failFor(a, exit == null ? Exit.AUTH_REQUIRED : exit));
        if ("developer_account_required".equals(a.member("lingara_code")) && !fail.hints().contains(Hints.PORTAL_LINE)) {
            fail.hint(Hints.PORTAL_LINE);
        }
        return fail;
    }

    /** The CI pair, when both halves are set (D3 rule 1); {@code null} otherwise. */
    public static String[] ciPair(Ctx ctx) {
        String id = ctx.app.env.get("LINGARA_PLATFORM_CLIENT_ID");
        String secret = ctx.app.env.get("LINGARA_PLATFORM_CLIENT_SECRET");
        return id == null || secret == null ? null : new String[] {id, secret};
    }

    /** A token-endpoint form {@code POST}: no bearer, classified as a token route. */
    public static JsonNode tokenPost(Ctx ctx, Fixed fixed, List<Pair> form, Auth auth) {
        Route route = fixed.route();
        Wire.In reply = ctx.app.http.send(Request.of(route).withForm(form), auth);
        if (!ok(reply)) {
            throw refusal(route, reply, false);
        }
        return bodyJson(reply, route);
    }

    private static Tokens mint(Ctx ctx, String[] pair) {
        List<Pair> form = List.of(new Pair("grant_type", "client_credentials"), new Pair("scope", Tokens.SCOPES));
        return Tokens.read(tokenPost(ctx, Fixed.TOKEN, form, Auth.basic(pair[0], pair[1])));
    }

    private static Session open(Ctx ctx) {
        String[] pair = ciPair(ctx);
        if (pair != null) {
            Tokens tokens = mint(ctx, pair);
            return new Session(pair, null, tokens.access(), null, tokens.scope() == null ? Tokens.SCOPES : tokens.scope());
        }
        ProfileRecord record = ctx.app.stores.load(ctx.profile);
        if (record == null) {
            throw Fail.auth("not signed in: run `lingara login`");
        }
        Long at = Rfc3339.parse(record.accessExpiresAt);
        boolean stale = at == null || at - ctx.app.http.clock().now() < Tokens.REFRESH_SKEW_SECS;
        ctx.app.session = Session.stored(record, record.email);
        if (stale) {
            refresh(ctx);
        }
        return ctx.app.session;
    }

    /** The bearer token, opening the session on first use. */
    public static String bearer(Ctx ctx) {
        if (ctx.app.session == null) {
            ctx.app.session = open(ctx);
        }
        return ctx.app.session.access;
    }

    /** Rule 2's one refresh: the stored refresh token, or a fresh CI mint. Never deletes the record. */
    private static void refresh(Ctx ctx) {
        Session session = ctx.app.session;
        if (session == null) {
            throw Fail.auth("not signed in: run `lingara login`");
        }
        if (session.ci != null) {
            session.access = mint(ctx, session.ci).access();
            return;
        }
        ProfileRecord record = session.record.copy();
        List<Pair> form = List.of(
                new Pair("grant_type", "refresh_token"),
                new Pair("refresh_token", record.refreshToken),
                new Pair("client_id", ctx.app.endpoints.clientId()));
        JsonNode tokens;
        try {
            tokens = tokenPost(ctx, Fixed.TOKEN, form, Auth.NONE);
        } catch (Fail fail) {
            // A refused refresh says "run lingara login", unless it is D8's dark signal.
            throw fail.exit() == Exit.AUTH_REQUIRED ? fail.hint("run `lingara login`") : fail;
        }
        Tokens.read(tokens).apply(record, ctx.app.http.clock().now());
        saveRecord(ctx, record);
        ctx.app.session = Session.stored(record, record.email);
    }

    /** Writes the profile record, printing the store's one fallback warning. */
    public static void saveRecord(Ctx ctx, ProfileRecord record) {
        String warning = ctx.app.stores.save(ctx.profile, record);
        if (warning != null) {
            ctx.out.note(warning);
        }
    }

    /** One authenticated call: send, refresh and replay once on a {@code 401}, classify anything else. */
    public static JsonNode call(Ctx ctx, Request request) {
        Route route = request.route();
        Wire.In reply = ctx.app.http.send(request, Auth.bearer(bearer(ctx)));
        if (ok(reply)) {
            return bodyJson(reply, route);
        }
        if (Classify.classify(Answer.of(route, reply, true)) != null) {
            throw refusal(route, reply, true);
        }
        refresh(ctx);
        Wire.In replay = ctx.app.http.send(request, Auth.bearer(bearer(ctx)));
        if (ok(replay)) {
            return bodyJson(replay, route);
        }
        if (replay.status() == 401) {
            throw Fail.auth("the platform refused the credentials after a refresh: run `lingara login`");
        }
        throw refusal(route, replay, true);
    }

    /** The email {@code --confirm} and the typed line are compared against. */
    public static String email(Ctx ctx) {
        bearer(ctx);
        if (ctx.app.session.email != null) {
            return ctx.app.session.email;
        }
        JsonNode me = call(ctx, Request.of(Fixed.DEVELOPER_ME.route()));
        String email = Json.text(me, "email", "");
        ctx.app.session.email = email;
        return email;
    }
}
