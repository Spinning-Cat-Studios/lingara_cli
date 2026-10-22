package com.getlingara.cli.java.supervise;

import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Env;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * cloudflared's lookup (ADR 16.10.26ae D8): {@code LINGARA_CLOUDFLARED} and then only that path,
 * else {@code cloudflared} on {@code PATH}, refused below {@code tools.toml}'s minimum. Found,
 * never fetched; checked offline, before any request (D7 step 2).
 */
public final class Cloudflared {
    private Cloudflared() {}

    /** {@code tool_missing} with D8's hint, the exit row's own message. */
    public static Fail missing() {
        return Fail.message(Exit.TOOL_MISSING, "tool_missing_cloudflared");
    }

    private static Path onPath(String name, Env env) {
        String path = env.get("PATH");
        if (path == null) {
            return null;
        }
        String exe = System.getProperty("os.name", "").startsWith("Windows") ? name + ".exe" : name;
        for (String dir : path.split(File.pathSeparator)) {
            Path candidate = Path.of(dir.isEmpty() ? "." : dir, exe);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The first {@code a.b(.c…)} on {@code cloudflared --version}'s first line; {@code null} for none. */
    static List<Long> versionOf(Path binary) {
        try {
            Process p = new ProcessBuilder(binary.toString(), "--version")
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            String first = text.lines().findFirst().orElse("");
            for (String word : first.trim().split("\\s+")) {
                List<Long> v = dotted(word);
                if (v != null && v.size() >= 2) {
                    return v;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<Long> dotted(String word) {
        List<Long> out = new ArrayList<>();
        for (String part : word.split("\\.", -1)) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                return null;
            }
            out.add(Long.parseLong(part));
        }
        return out;
    }

    /** Field-by-field comparison, as Rust's slices compare: {@code a >= b}. */
    static boolean atLeast(List<Long> a, List<Long> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Long.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c > 0;
            }
        }
        return a.size() >= b.size();
    }

    /** Lookup and version check, offline (D7 step 2). */
    public static Path find(Env env) {
        String explicit = env.get("LINGARA_CLOUDFLARED");
        Path binary = explicit != null ? (Files.isRegularFile(Path.of(explicit)) ? Path.of(explicit) : null) : onPath("cloudflared", env);
        if (binary == null) {
            throw missing();
        }
        List<Long> version = versionOf(binary);
        if (version == null || !atLeast(version, Assets.cloudflaredMinVersion())) {
            throw missing();
        }
        return binary;
    }
}
