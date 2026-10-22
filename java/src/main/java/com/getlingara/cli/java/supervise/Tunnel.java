package com.getlingara.cli.java.supervise;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One cloudflared incarnation (ADR 16.10.26ae D8). <strong>URL</strong>: the CLI picks a free
 * loopback port for {@code --metrics} and polls its {@code /quicktunnel}, falling back to the
 * {@code https://…trycloudflare.com} line on stderr. <strong>Ready</strong>: the probe URL
 * ({@code LINGARA_TUNNEL_PROBE_URL}, default {@code https://{hostname}/}) answers anything but
 * Cloudflare's {@code 530}. Each wait is 30 s.
 */
public final class Tunnel {
    static final long WAIT_MS = 30_000;
    private static final long POLL_MS = 250;
    private static final int KEPT_LINES = 20;
    private static final String DEFAULT_PROBE = "https://{hostname}/";

    private final Group group;
    private final String metrics;
    private final Deque<String> lines = new ArrayDeque<>();
    private volatile String loggedHost;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Tunnel(Group group, String metrics) {
        this.group = group;
        this.metrics = metrics;
    }

    /** A free loopback port, for cloudflared's metrics server. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw Fail.failure("a free port: " + e.getMessage());
        }
    }

    /** {@code cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>}. */
    public static Tunnel spawn(Path binary, int port) {
        String metrics = "127.0.0.1:" + freePort();
        ProcessBuilder command = new ProcessBuilder(
                List.of(binary.toString(), "tunnel", "--url", "http://127.0.0.1:" + port, "--metrics", metrics))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Group group;
        try {
            group = Group.spawn(command);
            group.process().getOutputStream().close();
        } catch (IOException e) {
            throw new Fail(Exit.TUNNEL_FAILED, "tunnel_failed", "cloudflared: " + e.getMessage());
        }
        Tunnel tunnel = new Tunnel(group, metrics);
        Thread keep = new Thread(tunnel::keep, "cloudflared-log");
        keep.setDaemon(true);
        keep.start();
        return tunnel;
    }

    /** The {@code https://…trycloudflare.com} hostname on one log line; {@code null} for none. */
    static String hostnameIn(String line) {
        int at = line.indexOf("https://");
        if (at < 0) {
            return null;
        }
        StringBuilder host = new StringBuilder();
        for (char c : line.substring(at + "https://".length()).toCharArray()) {
            if (!(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '.'))) {
                break;
            }
            host.append(c);
        }
        return host.toString().endsWith(".trycloudflare.com") ? host.toString() : null;
    }

    private void keep() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(group.process().getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String found = hostnameIn(line);
                if (found != null && loggedHost == null) {
                    loggedHost = found;
                }
                synchronized (lines) {
                    lines.addLast(line);
                    while (lines.size() > KEPT_LINES) {
                        lines.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            // cloudflared closed its stderr: it is ending.
        }
    }

    public boolean alive() {
        return group.exited() == null;
    }

    /** The last lines cloudflared wrote, for {@code tunnel_failed}'s reason. */
    public String tail() {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }

    private HttpResponse<byte[]> get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String quicktunnel() {
        HttpResponse<byte[]> response = get("http://" + metrics + "/quicktunnel");
        JsonNode answer = response == null ? null : Json.parse(response.body());
        String host = Json.text(answer, "hostname");
        return host == null || host.isEmpty() ? null : host;
    }

    /** The quick tunnel's hostname: metrics first, the log line second; {@code null} when none came. */
    public String hostname(BooleanSupplier stop) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && alive() && !stop.getAsBoolean()) {
            String host = quicktunnel();
            if (host == null) {
                host = loggedHost;
            }
            if (host != null) {
                return host;
            }
            Shell.sleep(POLL_MS);
        }
        return null;
    }

    /** Whether the probe reached the app through the tunnel: any answer but a {@code 530}, once a second, for 30 s. */
    public boolean ready(String host, String template, BooleanSupplier stop) {
        String url = (template == null ? DEFAULT_PROBE : template).replace("{hostname}", host).replace("{metrics}", metrics);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && alive() && !stop.getAsBoolean()) {
            HttpResponse<byte[]> response = get(url);
            if (response != null && response.statusCode() != 530) {
                return true;
            }
            Shell.sleep(1000);
        }
        return false;
    }

    public void stop() {
        group.stop(5000);
    }
}
