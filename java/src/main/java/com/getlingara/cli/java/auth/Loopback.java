package com.getlingara.cli.java.auth;

import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Interrupts;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.http.Url;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The loopback half of PKCE: answer each request on the listener until the one callback whose
 * {@code state} and {@code iss} are ours arrives, the 5 minutes run out, or Ctrl+C. The wait
 * watches {@link Interrupts}, so an interrupted login returns {@code interrupted} (D9).
 */
final class Loopback {
    private static final long LISTEN_FOR_MS = 300_000;
    private static final int POLL_MS = 200;
    private static final String CLOSE_TAB = "<!doctype html><title>Lingara</title><p>You can close this tab.</p>";

    /** A callback that is ours but carries no code: the developer declined. */
    private static final String DECLINED = "";

    private Loopback() {}

    /** The code the browser brought back; {@code null} when sign-in was declined. */
    static String waitForCode(ServerSocket listener, String state, String issuer) throws IOException {
        long deadline = System.currentTimeMillis() + LISTEN_FOR_MS;
        listener.setSoTimeout(POLL_MS);
        try (Interrupts.Watch watch = Interrupts.watch()) {
            while (System.currentTimeMillis() < deadline) {
                if (Interrupts.raised()) {
                    throw new Fail(Exit.INTERRUPTED, "interrupted", "interrupted");
                }
                String found = acceptOne(listener, state, issuer);
                if (found != null) {
                    return found.equals(DECLINED) ? null : found;
                }
            }
        }
        throw Fail.auth("no sign-in arrived within 5 minutes; run `lingara login` again");
    }

    private static String acceptOne(ServerSocket listener, String state, String issuer) throws IOException {
        try (Socket stream = listener.accept()) {
            return answer(stream, state, issuer);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    /** Answers one request; the callback's code ({@link #DECLINED} for none), if it is the one. */
    private static String answer(Socket stream, String state, String issuer) throws IOException {
        stream.setSoTimeout(5000);
        InputStream in = stream.getInputStream();
        byte[] buf = new byte[8192];
        int n = Math.max(0, in.read(buf));
        String head = new String(buf, 0, n, StandardCharsets.UTF_8);
        String[] words = head.split("\\s+");
        String target = words.length > 1 ? words[1] : "";
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        List<Pair> params = Url.parseQuery(q < 0 ? "" : target.substring(q + 1));
        String iss = param(params, "iss");
        boolean ours = path.equals("/callback")
                && state.equals(param(params, "state"))
                && iss != null
                && iss.replaceAll("/+$", "").equals(issuer);
        String status = ours ? "200 OK" : "400 Bad Request";
        String reply = "HTTP/1.1 " + status + "\r\ncontent-type: text/html\r\ncontent-length: " + CLOSE_TAB.length()
                + "\r\nconnection: close\r\n\r\n" + CLOSE_TAB;
        OutputStream out = stream.getOutputStream();
        out.write(reply.getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (!ours) {
            return null;
        }
        String code = param(params, "code");
        return code == null ? DECLINED : code;
    }

    private static String param(List<Pair> params, String key) {
        for (Pair p : params) {
            if (p.key().equals(key)) {
                return p.value();
            }
        }
        return null;
    }
}
