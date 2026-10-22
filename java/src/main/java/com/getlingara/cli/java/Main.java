package com.getlingara.cli.java;

import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Env;
import com.getlingara.cli.java.core.Interrupts;
import com.getlingara.cli.java.core.StdioTerminal;
import com.getlingara.cli.java.http.Http;
import com.getlingara.cli.java.http.JdkTransport;
import com.getlingara.cli.java.http.Wire;
import com.getlingara.cli.java.store.ConfigDir;
import com.getlingara.cli.java.store.FileStore;
import com.getlingara.cli.java.store.KeychainStore;
import com.getlingara.cli.java.store.Stores;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code lingara}, the Lingara developer CLI: the Java kit (ADR 16.10.26af). Every behaviour a
 * developer or CI job can observe is {@code conformance/CONTRACT.md}'s; this is one way of
 * keeping it, translated from the Rust reference kit.
 */
public final class Main {
    private Main() {}

    /** {@code lingara-cli/<semver> (java; <os>)}, contract B5, with Rust's OS words. */
    static String userAgent() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String word = os.startsWith("mac") ? "macos" : os.startsWith("windows") ? "windows" : os.replaceAll("[^a-z0-9_]", "");
        return "lingara-cli/" + Assets.version() + " (java; " + (word.isEmpty() ? "unknown" : word) + ")";
    }

    /**
     * {@code LC_ALL}, else {@code LC_MESSAGES}, else {@code LANG}, as BCP 47: {@code en_US.UTF-8}
     * is {@code en-US}; {@code C} and {@code POSIX} name no language.
     */
    static String acceptLanguage(Env env) {
        for (String name : new String[] {"LC_ALL", "LC_MESSAGES", "LANG"}) {
            String raw = env.get(name);
            if (raw != null) {
                String tag = raw.split("[.@]", -1)[0].replace('_', '-');
                return tag.isEmpty() || tag.equals("C") || tag.equals("POSIX") ? null : tag;
            }
        }
        return null;
    }

    /** The browser, unless {@code LINGARA_NO_BROWSER=1}: the platform's own opener, never AWT. */
    static boolean openBrowser(Env env, String url) {
        if ("1".equals(env.get("LINGARA_NO_BROWSER"))) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.startsWith("mac") ? List.of("open", url)
                : os.startsWith("windows") ? List.of("rundll32", "url.dll,FileProtocolHandler", url) : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static App production(Env env) {
        Assets.Endpoints endpoints = Assets.endpoints();
        Path configDir = ConfigDir.of(env);
        FileStore file = new FileStore(configDir == null ? Path.of(".") : configDir);
        Stores stores = new Stores(Stores.Mode.fromEnv(env.get("LINGARA_KEYRING")), new KeychainStore(), file);
        String portal = env.get("LINGARA_PLATFORM_URL") != null ? env.get("LINGARA_PLATFORM_URL") : endpoints.platformUrl();
        Http http = new Http(new JdkTransport(), Wire.SYSTEM_CLOCK, new String[] {endpoints.apiUrl(), portal},
                userAgent(), acceptLanguage(env));
        App.Parts parts = new App.Parts(http, env, stores, new StdioTerminal(), url -> openBrowser(env, url));
        return new App(parts, endpoints, configDir);
    }

    public static void main(String[] args) {
        Interrupts.install();
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        Outcome outcome = production(Env.fromProcess()).run(List.of(args), err);
        try (FileOutputStream stdout = new FileOutputStream(FileDescriptor.out)) {
            stdout.write(outcome.stdout());
            stdout.flush();
        } catch (IOException e) {
            // A closed stdout (a pipe whose reader left) has nowhere to report to.
        }
        err.flush();
        System.exit(outcome.exit().code());
    }
}
