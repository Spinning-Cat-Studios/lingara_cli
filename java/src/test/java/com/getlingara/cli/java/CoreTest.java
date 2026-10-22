package com.getlingara.cli.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Fakes.Script;
import com.getlingara.cli.java.cli.Mixins.ConfirmFlags;
import com.getlingara.cli.java.commands.Confirm;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Interrupts;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.StdioTerminal;
import com.getlingara.cli.java.core.Table;
import com.getlingara.cli.java.http.Answer;
import com.getlingara.cli.java.http.Classify;
import com.getlingara.cli.java.http.Wire;
import com.getlingara.cli.java.store.FileStore;
import com.getlingara.cli.java.store.ProfileRecord;
import com.getlingara.cli.java.store.Stores;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Signal;

/**
 * The Java kit's core, as ADR 16.10.26af AC17 lists it: the confirm ritual, the store selection,
 * the classifier's B2 rules, every {@code canvas-plans.json} vector, the B1 table, {@code
 * interrupted} returned as 130 on SIGINT, and TTY detection reading fds 0 and 2, never {@code
 * System.console()}.
 */
class CoreTest {
    @TempDir
    Path tmp;

    // 16.10.26af AC17
    @Test
    void core() throws Exception {
        confirmRitual();
        storeSelection();
        classifierRules();
        PlanVectors.check();
        b1Table();
        ttyReadsFdsZeroAndTwo();
        interruptedReturns130();
    }

    private static Fakes.Ran<Object> ritual(boolean interactive, ConfirmFlags flags, int[] reads, Script script, String... lines) {
        App app = Fakes.app(script, Fakes.terminal(interactive, lines));
        return Fakes.withCtx(app, false, ctx -> {
            try {
                return Confirm.destructive(ctx, flags, c -> {
                    reads[0]++;
                    return new Confirm.Prompt("OAuth client", "My server", "oc1", "Its tokens stop.", 2);
                });
            } catch (Fail f) {
                return f;
            }
        });
    }

    private static ConfirmFlags flags(boolean yes, String confirm) {
        ConfirmFlags f = new ConfirmFlags();
        f.yes = yes;
        f.confirm = confirm;
        return f;
    }

    private static String code(Object result) {
        return result instanceof Fail f ? f.code() : null;
    }

    /** 16.10.26ad D5: y/n, then the email; the flags replace both; a pipe is refused before any call. */
    private void confirmRitual() {
        int[] reads = {0};
        Script script = new Script();
        Fakes.Ran<Object> ok = ritual(true, flags(false, null), reads, script, "y", "  Dev@Conformance.INVALID ");
        assertEquals(Fakes.EMAIL, ok.result());
        assertEquals(1, reads[0]);
        assertTrue(script.log.isEmpty());
        assertTrue(ok.stderr().contains("Delete OAuth client My server (oc1)?\nIts tokens stop.\nObjects pinning it: 2. [y/N] "), ok.stderr());
        assertTrue(ok.stderr().contains("Type your developer email (d…@conformance.invalid) to confirm: "), ok.stderr());
        assertEquals(Fakes.EMAIL, ritual(true, flags(false, null), reads, script, "YES", Fakes.EMAIL).result());
        for (String answer : new String[] {"n", "", "yep"}) {
            Fakes.Ran<Object> declined = ritual(true, flags(false, null), reads, script, answer);
            assertEquals("confirm_declined", code(declined.result()));
            assertEquals(Exit.NOT_CONFIRMED, ((Fail) declined.result()).exit());
            assertFalse(declined.stderr().contains("Type your developer email"));
        }
        assertEquals("confirm_email_mismatch", code(ritual(true, flags(false, null), reads, script, "y", "someone@else.invalid", Fakes.EMAIL).result()));
        for (boolean interactive : new boolean[] {false, true}) {
            int[] none = {0};
            Fakes.Ran<Object> flagged = ritual(interactive, flags(true, "DEV@conformance.invalid"), none, script);
            assertEquals(Fakes.EMAIL, flagged.result());
            assertEquals(0, none[0]);
            assertEquals("", flagged.stderr());
        }
        assertEquals("confirm_email_mismatch", code(ritual(false, flags(true, "someone@else.invalid"), reads, script).result()));
        for (ConfirmFlags one : List.of(flags(true, null), flags(false, Fakes.EMAIL), flags(false, null))) {
            int[] none = {0};
            assertEquals("confirm_non_tty_refused", code(ritual(false, one, none, script).result()));
            assertEquals(0, none[0]);
        }
        assertTrue(script.log.isEmpty(), "no ritual sent a request");
        assertEquals("d…@example.com", Confirm.hint("dev@example.com"));
        assertEquals("…@example.com", Confirm.hint("d@example.com"));
        assertEquals("a@b.c", Confirm.canonical("  A@B.c\n"));
    }

    private static ProfileRecord record(String email) {
        ProfileRecord r = new ProfileRecord();
        r.email = email;
        r.accessToken = "lgr_at_x";
        r.refreshToken = "lgr_rt_x";
        return r;
    }

    /** 16.10.26ad D4: keychain first, the file as fallback with one warning, never config.toml. */
    private void storeSelection() throws Exception {
        Path dir = tmp.resolve("lingara");
        Stores present = new Stores(Stores.Mode.AUTO, new Fakes.MemoryStore(false), new FileStore(dir));
        assertNull(present.save("default", record("a@b.c")));
        assertEquals(record("a@b.c"), present.load("default"));
        assertFalse(Files.exists(present.file().path()));
        Stores fallback = new Stores(Stores.Mode.AUTO, new Fakes.MemoryStore(true), new FileStore(dir));
        assertTrue(fallback.save("default", record("a@b.c")).contains("LINGARA_KEYRING=keychain"));
        assertNull(fallback.save("default", record("a@b.c")), "warned once");
        assertEquals(record("a@b.c"), fallback.load("default"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fallback.file().path())));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        JsonNode shape = Json.parse(Files.readString(fallback.file().path()));
        assertEquals(1, shape.path("schema").asInt());
        assertEquals("a@b.c", shape.path("profiles").path("default").path("email").asText());
        Path other = tmp.resolve("other");
        assertNull(new Stores(Stores.Mode.FILE, new Fakes.MemoryStore(false), new FileStore(other)).save("p", record("x@y.z")));
        Stores strict = new Stores(Stores.Mode.KEYCHAIN, new Fakes.MemoryStore(true), new FileStore(other));
        assertEquals(Exit.AUTH_REQUIRED, assertThrows(Fail.class, () -> strict.save("p", record("x@y.z"))).exit());
        assertThrows(Fail.class, () -> strict.load("p"));
        assertFalse(Files.exists(dir.resolve("config.toml")) || Files.exists(other.resolve("config.toml")));
        Path fixture = tmp.resolve("fixture");
        Files.createDirectories(fixture);
        Files.copy(Fakes.repo().resolve("conformance/fixtures/credentials/stored.json"), fixture.resolve("credentials.json"));
        ProfileRecord stored = new FileStore(fixture).load("default");
        assertEquals(Fakes.EMAIL, stored.email);
        assertEquals("2099-01-01T00:00:00Z", stored.accessExpiresAt);
    }

    private static Exit exitOf(String route, int status, String body) {
        boolean token = route.equals("oauth.token") || route.equals("oauth.device_authorization");
        boolean platform = route.contains(".") && !token && !route.startsWith("canvas.");
        Wire.In reply = new Wire.In(status, new ArrayList<>(), body.getBytes(StandardCharsets.UTF_8));
        return Classify.classify(new Answer(route, token, platform, !token, reply));
    }

    /** B2's rules, first match winning; {@code null} is rule 2's refresh. */
    private void classifierRules() {
        assertEquals(Exit.PLATFORM_DISABLED, exitOf("app.list", 404, "{\"code\":\"platform_disabled\",\"error\":\"x\"}"));
        assertEquals(Exit.PLATFORM_DISABLED, exitOf("developer.me", 403, "{\"code\":\"platform_access_not_enabled\",\"error\":\"x\"}"));
        assertEquals(Exit.PLATFORM_DISABLED,
                exitOf("oauth.token", 400, "{\"error\":\"invalid_grant\",\"lingara_code\":\"platform_access_not_enabled\"}"));
        assertEquals(Exit.AUTH_REQUIRED, exitOf("oauth.token", 400, "{\"error\":\"invalid_grant\",\"error_description\":\"x\"}"));
        assertEquals(Exit.NOT_FOUND, exitOf("app.get", 404, "{\"code\":\"object_not_found\",\"error\":\"x\"}"));
        assertEquals(Exit.REFUSED, exitOf("developer.me", 404, "{\"code\":\"developer_not_found\",\"error\":\"x\"}"));
        assertEquals(Exit.FAILURE, exitOf("app.list", 404, ""));
        assertNull(exitOf("app.list", 401, ""));
        assertEquals(Exit.AUTH_REQUIRED, exitOf("oauth.token", 401, "{\"error\":\"invalid_client\"}"));
        assertEquals(Exit.UNAVAILABLE, exitOf("oauth.token", 429, "{\"error\":\"slow\"}"));
        assertEquals(Exit.UNAVAILABLE, exitOf("oauth.token", 503, "{\"error\":\"down\"}"));
        assertEquals(Exit.FAILURE, exitOf("app.list", 405, ""));
        assertEquals(Exit.FAILURE, exitOf("app.list", 404, "<html>"));
        for (int status : new int[] {400, 403, 409, 422}) {
            assertEquals(Exit.REFUSED, exitOf("app.create", status, "{\"code\":\"c\",\"error\":\"e\"}"));
        }
        String held = "{\"code\":\"dev_session_held\",\"error\":\"x\",\"lease_expires_at\":\"2026-10-20T15:00:00Z\"}";
        assertEquals(Exit.DEV_SESSION_HELD, exitOf("app_dev.session_open", 409, held));
        assertEquals(Exit.DEV_SESSION_LOST, exitOf("app_dev.session_renew", 409, "{\"code\":\"dev_session_expired\",\"error\":\"x\"}"));
        assertEquals(Exit.REFUSED, exitOf("app.create", 409, held));
        assertEquals(Exit.REFUSED, exitOf("app_dev.patch", 409, "{\"code\":\"version_conflict\",\"error\":\"x\"}"));
    }

    /** B1: padded to the widest cell, two spaces apart, {@code -} for empty, no trailing whitespace. */
    private void b1Table() {
        String text = Table.render(List.of("ID", "NAME", "SERVING"),
                List.of(List.of("7d1e", "Flashcards", "1.0.0"), List.of("7d1e2f30-long", "Tone drills", "")));
        assertEquals("ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n", text);
        assertTrue(text.lines().noneMatch(l -> l.endsWith(" ")));
        String wide = "x".repeat(200);
        assertTrue(Table.render(List.of("A"), List.of(List.of(wide))).contains(wide));
        assertEquals("SCOPES  B\na       b\n", Table.render(List.of("SCOPES", "B"), List.of(List.of("a", "b"))));
        assertEquals("a:read,b:write", Table.cell(Json.parse("[\"a:read\",\"b:write\"]")));
        assertEquals("-", Table.cell(Json.parse("null")));
        assertEquals("{\"a\":1}", Table.cell(Json.parse("{\"a\":1}")));
    }

    /** The descriptors the terminal test reads: 0 and 2, both, and no source calls System.console(). */
    private void ttyReadsFdsZeroAndTwo() throws IOException {
        List<Integer> asked = new ArrayList<>();
        assertTrue(new StdioTerminal(fd -> asked.add(fd) || true).interactive());
        assertEquals(List.of(0, 2), asked);
        assertFalse(new StdioTerminal(fd -> fd == 0).interactive(), "stdin alone is not a terminal session");
        assertFalse(new StdioTerminal(fd -> fd == 2).interactive(), "stderr alone is not one either");
        try (Stream<Path> sources = Files.walk(Fakes.repo().resolve("java/src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(source)) {
                    String code = line.strip();
                    boolean comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//");
                    assertFalse(!comment && code.contains("System.console("), source + " calls System.console()");
                }
            }
        }
    }

    /**
     * A real SIGINT while {@code login} waits on its loopback: trapped, the listener closed, and
     * {@code interrupted} returned as 130 rather than the JVM dying.
     */
    private void interruptedReturns130() {
        Interrupts.install();
        App app = Fakes.app(new Script(), Fakes.terminal(false), url -> {
            Thread later = new Thread(() -> {
                sleep(300);
                Signal.raise(new Signal("INT"));
            });
            later.start();
            return true;
        });
        Outcome outcome = app.run(List.of("login"), new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(Exit.INTERRUPTED, outcome.exit());
        assertEquals(130, outcome.exit().code());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
