package com.getlingara.cli.java.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.http.Auth;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Route;
import com.getlingara.cli.java.http.Routes.Fixed;
import com.getlingara.cli.java.http.Wire;
import java.util.List;

/**
 * {@code login --device} (RFC 8628, 16.10.26u §7): for SSH sessions and machines without a
 * browser. {@code authorization_pending} keeps polling, {@code slow_down} adds 5 s, {@code
 * expired_token} and {@code access_denied} are {@code auth_required}, and any answer carrying
 * B4's {@code lingara_code} is {@code platform_disabled} (16.10.26ad D8).
 */
public final class Device {
    private static final String GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    private Device() {}

    /** The token response once the developer has approved the code. */
    public static JsonNode login(Ctx ctx) {
        String clientId = ctx.app.endpoints.clientId();
        List<Pair> form = List.of(new Pair("client_id", clientId), new Pair("scope", Tokens.SCOPES));
        JsonNode start = Calls.tokenPost(ctx, Fixed.DEVICE_AUTHORIZATION, form, Auth.NONE);
        ctx.out.note(Assets.message("login_device_code",
                "verification_uri", Json.text(start, "verification_uri", ""),
                "user_code", Json.text(start, "user_code", "")));
        String complete = Json.text(start, "verification_uri_complete");
        if (complete != null) {
            ctx.out.note(complete);
        }
        JsonNode interval = Json.at(start, "interval");
        JsonNode expires = Json.at(start, "expires_in");
        long[] timing = {
            interval.isIntegralNumber() ? interval.asLong() : 5,
            expires.isIntegralNumber() ? expires.asLong() : 900};
        return poll(ctx, Json.text(start, "device_code", ""), clientId, timing);
    }

    /** {@code timing} is {interval, expires_in}; the interval grows on {@code slow_down}. */
    private static JsonNode poll(Ctx ctx, String deviceCode, String clientId, long[] timing) {
        Route route = Fixed.TOKEN.route();
        List<Pair> form = List.of(new Pair("grant_type", GRANT), new Pair("device_code", deviceCode), new Pair("client_id", clientId));
        long waited = 0;
        while (true) {
            ctx.app.http.clock().sleep(timing[0]);
            waited += timing[0];
            Wire.In reply = ctx.app.http.send(Request.of(route).withForm(form), Auth.NONE);
            if (reply.status() >= 200 && reply.status() < 300) {
                JsonNode body = Json.parse(reply.body());
                if (body == null) {
                    throw Fail.failure("unexpected response from oauth.token");
                }
                return body;
            }
            JsonNode body = Json.parse(reply.body());
            String error = body != null && body.has("lingara_code") ? null : Json.text(body, "error");
            if (!keepPolling(error, waited < timing[1], timing)) {
                throw stopped(error, route, reply);
            }
        }
    }

    private static boolean keepPolling(String error, boolean inTime, long[] timing) {
        if (inTime && "slow_down".equals(error)) {
            timing[0] += 5;
            return true;
        }
        return inTime && "authorization_pending".equals(error);
    }

    private static Fail stopped(String error, Route route, Wire.In reply) {
        if (error != null && List.of("authorization_pending", "slow_down", "expired_token").contains(error)) {
            return Fail.auth("the code expired before it was approved; run `lingara login --device` again");
        }
        if ("access_denied".equals(error)) {
            return new Fail(Exit.AUTH_REQUIRED, "access_denied", "sign-in was declined");
        }
        return Calls.refusal(route, reply, false);
    }
}
