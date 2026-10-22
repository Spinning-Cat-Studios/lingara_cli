package com.getlingara.cli.java.supervise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * The children {@code apps dev} runs (ADR 16.10.26ae D5, D7 steps 4 and 8): the app's
 * environment, built from the parent's with the CLI's own platform credentials scrubbed; the
 * platform shell ({@code sh -c}, {@code cmd /C}); the relay of its output; and the wait for its
 * port.
 */
public final class Shell {
    /** The CLI's own credentials, never the app's (D5): {@code LINGARA_PLATFORM_*}. */
    public static final String SCRUBBED_PREFIX = "LINGARA_PLATFORM_";

    private Shell() {}

    /** {@code line} through the platform shell, exactly as the developer would type it. */
    public static ProcessBuilder shell(String line) {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return new ProcessBuilder(windows ? List.of("cmd", "/C", line) : List.of("sh", "-c", line));
    }

    /** The parent's environment, minus the CLI's credentials, plus {@code extra} (later entries win). */
    public static Map<String, String> childEnv(Map<String, String> parent, List<String[]> extra) {
        Map<String, String> env = new TreeMap<>();
        parent.forEach((k, v) -> {
            if (!k.startsWith(SCRUBBED_PREFIX)) {
                env.put(k, v);
            }
        });
        extra.forEach(pair -> env.put(pair[0], pair[1]));
        return env;
    }

    /** Copies {@code source} to {@code err} line by line, each line after {@code prefix}, on its own thread. */
    public static void relay(InputStream source, String prefix, PrintStream err) {
        Thread relay = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    synchronized (err) {
                        err.print(prefix + line + "\n");
                        err.flush();
                    }
                }
            } catch (IOException e) {
                // The child closed its end: nothing more to relay.
            }
        }, "relay");
        relay.setDaemon(true);
        relay.start();
    }

    /**
     * Whether {@code 127.0.0.1:port} accepts a connection before {@code timeoutMs}, giving up early
     * when {@code stop} says so.
     */
    public static boolean waitListening(int port, long timeoutMs, BooleanSupplier stop) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return true;
            } catch (IOException e) {
                // Not yet listening.
            }
            if (stop.getAsBoolean()) {
                return false;
            }
            sleep(100);
        }
        return false;
    }

    /** A sleep that a stray interrupt shortens rather than aborts. */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
