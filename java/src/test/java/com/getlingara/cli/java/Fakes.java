package com.getlingara.cli.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.auth.Tokens;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Env;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Output;
import com.getlingara.cli.java.core.Terminal;
import com.getlingara.cli.java.http.Http;
import com.getlingara.cli.java.http.Wire;
import com.getlingara.cli.java.store.CredentialStore;
import com.getlingara.cli.java.store.FileStore;
import com.getlingara.cli.java.store.ProfileRecord;
import com.getlingara.cli.java.store.Stores;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The unit tests' world: a scripted transport, a terminal, a clock that never sleeps and an
 * in-memory keychain holding the conformance fixture's developer. Every seam {@link App} has,
 * faked once, as the reference kit's {@code fakes_tests.rs} does.
 */
public final class Fakes {
    public static final String EMAIL = "dev@conformance.invalid";

    private Fakes() {}

    /** Requests answered in order; each names the {@code METHOD /path} it expects, else a {@code 599}. */
    public static final class Script implements Wire.Transport {
        final Deque<Object[]> answers = new ArrayDeque<>();
        public final List<Wire.Out> log = new ArrayList<>();

        public Script then(String expect, int status, JsonNode body) {
            byte[] bytes = body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
            answers.add(new Object[] {expect, new Wire.In(status, new ArrayList<>(), bytes)});
            return this;
        }

        /** The last scripted answer gains a header. */
        public Script header(String name, String value) {
            ((Wire.In) answers.peekLast()[1]).headers().add(new String[] {name, value});
            return this;
        }

        public List<String> sent() {
            List<String> out = new ArrayList<>();
            log.forEach(r -> out.add(r.method() + " " + pathOf(r.url())));
            return out;
        }

        public JsonNode body(int i) {
            return Json.parse(log.get(i).body());
        }

        @Override
        public Wire.In send(Wire.Out request) throws IOException {
            log.add(request);
            String got = request.method() + " " + pathOf(request.url());
            Object[] next = answers.poll();
            if (next == null) {
                throw new IOException("connection reset");
            }
            String want = (String) next[0];
            if (got.equals(want) || got.split("\\?")[0].equals(want)) {
                return (Wire.In) next[1];
            }
            return new Wire.In(599, List.of(), ("expected " + want).getBytes(StandardCharsets.UTF_8));
        }
    }

    static String pathOf(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? "/" : rest.substring(slash);
    }

    /** A terminal that answers its lines in order; {@code null} once they run out. */
    public static Terminal terminal(boolean interactive, String... lines) {
        Deque<String> queue = new ArrayDeque<>();
        for (String l : lines) {
            queue.add(l + "\n");
        }
        return new Terminal() {
            @Override
            public boolean interactive() {
                return interactive;
            }

            @Override
            public String readLine() {
                return queue.poll();
            }
        };
    }

    public static final Wire.Clock CLOCK = new Wire.Clock() {
        @Override
        public long now() {
            return 1_760_000_000L;
        }

        @Override
        public void sleep(long secs) {}
    };

    /** The in-memory keychain; {@code unavailable} plays a machine with none. */
    public static final class MemoryStore implements CredentialStore {
        public final Map<String, ProfileRecord> records = new HashMap<>();
        final boolean unavailable;

        public MemoryStore(boolean unavailable) {
            this.unavailable = unavailable;
        }

        private void open() throws StoreException {
            if (unavailable) {
                throw StoreException.unavailable("no keychain here");
            }
        }

        @Override
        public ProfileRecord load(String profile) throws StoreException {
            open();
            ProfileRecord r = records.get(profile);
            return r == null ? null : r.copy();
        }

        @Override
        public void save(String profile, ProfileRecord record) throws StoreException {
            open();
            records.put(profile, record.copy());
        }

        @Override
        public void delete(String profile) throws StoreException {
            open();
            records.remove(profile);
        }
    }

    public static ProfileRecord storedRecord() {
        ProfileRecord r = new ProfileRecord();
        r.developerId = "0b9c4f0e-0000-4000-8000-0000000000de";
        r.email = EMAIL;
        r.scopes = Tokens.SCOPES;
        r.refreshToken = "lgr_rt_conformance_stored";
        r.accessToken = "lgr_at_conformance_stored";
        r.accessExpiresAt = "2099-01-01T00:00:00Z";
        return r;
    }

    /** An app signed in as the fixture developer, over {@code script}. */
    public static App app(Script script, Terminal terminal, Function<String, Boolean> browser) {
        MemoryStore keychain = new MemoryStore(false);
        keychain.records.put("default", storedRecord());
        Http http = new Http(script, CLOCK, new String[] {"http://mock", "http://mock"}, "lingara-cli/0.0.0 (java; test)", "en-US");
        Stores stores = new Stores(Stores.Mode.KEYCHAIN, keychain, new FileStore(Path.of("/nonexistent")));
        return new App(new App.Parts(http, new Env(Map.of()), stores, terminal, browser::apply), Assets.endpoints(), null);
    }

    public static App app(Script script, Terminal terminal) {
        return app(script, terminal, url -> false);
    }

    /** What a context run left: its result, stdout and stderr. */
    public record Ran<T>(T result, String stdout, String stderr) {}

    /** Runs {@code f} against a fresh context over {@code app}, with {@code --json} as asked. */
    public static <T> Ran<T> withCtx(App app, boolean json, Function<Ctx, T> f) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Output out = new Output(new PrintStream(err, true, StandardCharsets.UTF_8), json);
        T result = f.apply(new Ctx(app, out, "default", false));
        return new Ran<>(result, new String(out.stdout(), StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** The repository root, for the shared vectors the tests read in place. */
    public static Path repo() {
        return Path.of(System.getProperty("lingara.repo", "..")).toAbsolutePath();
    }
}
