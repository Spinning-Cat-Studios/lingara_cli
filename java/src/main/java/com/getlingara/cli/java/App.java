package com.getlingara.cli.java;

import com.getlingara.cli.java.auth.Session;
import com.getlingara.cli.java.cli.Cli;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Env;
import com.getlingara.cli.java.core.Errors;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Output;
import com.getlingara.cli.java.core.Terminal;
import com.getlingara.cli.java.http.Http;
import com.getlingara.cli.java.store.ConfigDir;
import com.getlingara.cli.java.store.Stores;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * One run: parse argv, pick the profile and the API, dispatch, and turn the result into an exit
 * code. {@link #run} is the one entry point, used by {@code Main} and, in-process, by canvas
 * apply's inner verbs (16.10.26ad D7 step 4), which share this app's signed-in session, so no
 * second token exchange happens.
 */
public final class App {
    public final Http http;
    public final Env env;
    public final Stores stores;
    public final Terminal terminal;
    /** Opens a URL in the browser; {@code false} when it could not. */
    public final Predicate<String> browser;
    public final Assets.Endpoints endpoints;
    public final Path configDir;
    public Session session;

    /** The parts of an app, so a test can pass fakes for each. */
    public record Parts(Http http, Env env, Stores stores, Terminal terminal, Predicate<String> browser) {}

    public App(Parts parts, Assets.Endpoints endpoints, Path configDir) {
        this.http = parts.http();
        this.env = parts.env();
        this.stores = parts.stores();
        this.terminal = parts.terminal();
        this.browser = parts.browser();
        this.endpoints = endpoints;
        this.configDir = configDir;
    }

    /** Runs {@code argv} (without the binary's name), writing stderr to {@code err}. */
    public Outcome run(List<String> argv, PrintStream err) {
        boolean jsonFlag = argv.contains("--json");
        Cli.Parsed parsed;
        try {
            parsed = Cli.parse(argv);
        } catch (Fail fail) {
            return finish(new Output(err, jsonFlag), fail);
        }
        if (parsed.text() != null) {
            return new Outcome(Exit.OK, parsed.text().getBytes(StandardCharsets.UTF_8), null);
        }
        String profile = parsed.profile() != null ? parsed.profile() : env.get("LINGARA_PROFILE");
        boolean chosen = profile != null;
        profile = chosen ? profile : "default";
        ConfigDir.ProfileConfig config = ConfigDir.profile(configDir, profile);
        http.api(apiBase(config.apiUrl()));
        Output out = new Output(err, parsed.json() || "json".equals(config.output()));
        try {
            parsed.verb().run(new Ctx(this, out, profile, chosen));
            return new Outcome(Exit.OK, out.stdout(), null);
        } catch (Fail fail) {
            return finish(out, fail);
        }
    }

    /**
     * 16.10.26ad D3's order: {@code LINGARA_API_URL}, then {@code platform.toml}'s {@code
     * [platform] api}, then the profile's {@code api_url}, then {@code endpoints.json}'s. The
     * {@code apps} commands that read a {@code platform.toml} apply its slot when they open the
     * file (16.10.26ae D4); everything else never reads one.
     */
    private String apiBase(String profileApi) {
        String chosen = env.get("LINGARA_API_URL") != null ? env.get("LINGARA_API_URL") : profileApi;
        return chosen != null ? chosen : endpoints.apiUrl();
    }

    private static Outcome finish(Output out, Fail fail) {
        for (String line : Errors.lines(fail, out.json())) {
            out.note(line);
        }
        return new Outcome(fail.exit(), out.stdout(), fail);
    }
}
