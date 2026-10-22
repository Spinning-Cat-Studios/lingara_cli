package com.getlingara.cli.java.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.http.Auth;
import com.getlingara.cli.java.http.Routes.Fixed;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * {@code login}'s default: PKCE over a loopback redirect (RFC 8252, 16.10.26u §6). The kit binds
 * {@code 127.0.0.1:0}, opens the portal's authorize page, and accepts exactly one callback whose
 * {@code state} matches and whose {@code iss} is the portal base (RFC 9207). {@link Loopback}
 * gives up after 5 minutes, or at Ctrl+C.
 */
public final class Pkce {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {}

    static String randomText() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 7636's S256: the verifier's SHA-256, base64url without padding. */
    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK carries SHA-256", e);
        }
    }

    /** The token response for the code the browser brought back. */
    public static JsonNode login(Ctx ctx) {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            String verifier = randomText();
            String state = randomText();
            String redirect = "http://127.0.0.1:" + listener.getLocalPort() + "/callback";
            String clientId = ctx.app.endpoints.clientId();
            List<Pair> query = List.of(
                    new Pair("response_type", "code"),
                    new Pair("client_id", clientId),
                    new Pair("redirect_uri", redirect),
                    new Pair("code_challenge", challenge(verifier)),
                    new Pair("code_challenge_method", "S256"),
                    new Pair("state", state),
                    new Pair("scope", Tokens.SCOPES));
            String url = ctx.app.http.url(Fixed.AUTHORIZE.route(), Map.of(), query);
            ctx.out.note(url);
            ctx.out.note(Assets.message("login_browser_wait"));
            ctx.app.browser.test(url);
            String issuer = ctx.app.http.portal().replaceAll("/+$", "");
            String code = Loopback.waitForCode(listener, state, issuer);
            if (code == null) {
                throw new Fail(Exit.AUTH_REQUIRED, "access_denied", "sign-in was declined");
            }
            List<Pair> form = List.of(
                    new Pair("grant_type", "authorization_code"),
                    new Pair("code", code),
                    new Pair("redirect_uri", redirect),
                    new Pair("code_verifier", verifier),
                    new Pair("client_id", clientId));
            return Calls.tokenPost(ctx, Fixed.TOKEN, form, Auth.NONE);
        } catch (IOException e) {
            throw Fail.failure("could not listen on 127.0.0.1: " + e.getMessage());
        }
    }
}
