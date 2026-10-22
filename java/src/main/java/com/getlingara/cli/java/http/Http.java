package com.getlingara.cli.java.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requests: one function spells a route onto a base, adds contract B5's headers, retries what
 * 16.10.26ad D6 lets it retry, and hands back the raw answer. Classifying it is {@link
 * Classify}'s; credentials are {@code auth.Calls}'.
 */
public final class Http {
    /** At most two retries of an idempotent request, waiting at most this long in all (D6). */
    private static final long RETRY_BUDGET_SECS = 30;
    private static final int RETRIES = 2;
    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT", "PATCH", "DELETE");

    private final Wire.Transport transport;
    private final Wire.Clock clock;
    private final String userAgent;
    private final String acceptLanguage;
    private String api;
    private final String portal;

    public Http(Wire.Transport transport, Wire.Clock clock, String[] bases, String userAgent, String acceptLanguage) {
        this.transport = transport;
        this.clock = clock;
        this.api = bases[0];
        this.portal = bases[1];
        this.userAgent = userAgent;
        this.acceptLanguage = acceptLanguage;
    }

    public Wire.Clock clock() {
        return clock;
    }

    public String api() {
        return api;
    }

    public void api(String base) {
        api = base;
    }

    public String portal() {
        return portal;
    }

    /** The route's absolute URL with {@code vars} filled and {@code query} appended. */
    public String url(Route route, Map<String, String> vars, List<Pair> query) {
        String base = route.portal() ? portal : api;
        String path = route.path();
        for (Map.Entry<String, String> v : vars.entrySet()) {
            path = path.replace("{" + v.getKey() + "}", Url.encode(v.getValue()));
        }
        String joined = query.isEmpty() ? "" : "?" + Url.form(query);
        return stripSlashes(base) + path + joined;
    }

    private static String stripSlashes(String base) {
        String out = base;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private Wire.Out wire(Request request, Auth auth) {
        List<String[]> headers = new ArrayList<>();
        headers.add(new String[] {"user-agent", userAgent});
        headers.add(new String[] {"accept", "application/json"});
        if (acceptLanguage != null) {
            headers.add(new String[] {"accept-language", acceptLanguage});
        }
        if (auth.header() != null) {
            headers.add(new String[] {"authorization", auth.header()});
        }
        byte[] body = new byte[0];
        if (request.json() != null) {
            headers.add(new String[] {"content-type", "application/json"});
            body = Json.write(request.json()).getBytes(StandardCharsets.UTF_8);
        } else if (request.form() != null) {
            headers.add(new String[] {"content-type", "application/x-www-form-urlencoded"});
            body = Url.form(request.form()).getBytes(StandardCharsets.UTF_8);
        }
        String url = url(request.route(), request.vars(), request.query());
        return new Wire.Out(request.route().method(), url, headers, body);
    }

    /**
     * One request, with D6's retries: a {@code GET}, {@code PUT}, {@code PATCH} or {@code DELETE}
     * answered 429 or 503 with {@code Retry-After} is retried up to twice within 30 s. A {@code
     * POST} is never retried after a response (B5).
     */
    public Wire.In send(Request request, Auth auth) {
        Wire.Out out = wire(request, auth);
        long waited = 0;
        for (int attempt = 0; ; attempt++) {
            Wire.In response = sendOnce(request.route(), out);
            if (attempt > 0 && replayedDeleteDone(out, response)) {
                return new Wire.In(204, List.of(), new byte[0]);
            }
            Long secs = retryWait(out, response);
            if (secs == null || attempt >= RETRIES || waited + secs > RETRY_BUDGET_SECS) {
                return response;
            }
            clock.sleep(secs);
            waited += secs;
        }
    }

    private Wire.In sendOnce(Route route, Wire.Out out) {
        try {
            return transport.send(out);
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new Fail(Exit.UNAVAILABLE, "unavailable", route.name() + " could not be reached: " + why);
        }
    }

    /** The wait an idempotent request may retry after. */
    private static Long retryWait(Wire.Out out, Wire.In response) {
        boolean busy = response.status() == 429 || response.status() == 503;
        if (!IDEMPOTENT.contains(out.method()) || !busy) {
            return null;
        }
        String after = response.header("retry-after");
        try {
            return after == null ? null : Long.parseLong(after.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A {@code DELETE} replayed after it succeeded answers a coded 404 (D6). */
    private static boolean replayedDeleteDone(Wire.Out out, Wire.In response) {
        JsonNode body = Json.parse(response.body());
        boolean coded = body != null && body.has("code");
        return out.method().equals("DELETE") && response.status() == 404 && coded;
    }
}
