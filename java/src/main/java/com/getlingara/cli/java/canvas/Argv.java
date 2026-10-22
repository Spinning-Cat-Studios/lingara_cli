package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 16.10.26ad D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own command
 * lines. A {@code refused} verdict stops apply with every refusal named; a {@code would_replace},
 * which the token door no longer sends, stops it before any write. A {@code would_update} webhook
 * carries exactly the flags its {@code changes} name: {@code --url}/{@code --event} (a new
 * version that keeps the endpoint and its secret) and {@code --enable} (no builder spec disables
 * one).
 */
public final class Argv {
    private Argv() {}

    /**
     * One word of an argv: text or an id already known (a platform object id), the id an earlier
     * step of this run makes ({@code MADE}), or an app op's manifest file ({@code MANIFEST}).
     */
    public record Part(String kind, String value) {
        public static final String TEXT = "text";
        public static final String MADE = "made";
        public static final String MANIFEST = "manifest";

        static Part text(String value) {
            return new Part(TEXT, value);
        }

        static Part made(String handle) {
            return new Part(MADE, handle);
        }
    }

    /**
     * What apply records for a step's node (16.10.26x D4: runtime ids only): {@code runtime_id},
     * {@code client_secret} (the secret its client's create minted, {@code client} naming that
     * handle), {@code secret_id} or {@code unchanged}.
     */
    public record Step(String handle, String kind, String action, List<Part> argv, String records, String client) {}

    /** Why apply stops before any write: every refused op (handle, code, error), or a replace. */
    public static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final List<String[]> refused;
        public final String replace;

        Stop(List<String[]> refused, String replace) {
            super("stop", null, false, false);
            this.refused = refused;
            this.replace = replace;
        }
    }

    /** Every step, or a {@link Stop} for why apply must stop before writing anything. */
    public static List<Step> steps(List<Plan.Op> ops, List<JsonNode> verdicts, Plan.Live live, Map<String, String> kept) {
        List<String[]> refused = new ArrayList<>();
        for (JsonNode v : verdicts) {
            if ("refused".equals(Json.text(v, "verdict"))) {
                refused.add(new String[] {Json.text(v, "handle", ""), Json.text(v, "code", ""), Json.text(v, "error", "")});
            }
        }
        if (!refused.isEmpty()) {
            throw new Stop(refused, null);
        }
        for (JsonNode v : verdicts) {
            if ("would_replace".equals(Json.text(v, "verdict"))) {
                throw new Stop(List.of(), Json.text(v, "handle", ""));
            }
        }
        List<Step> out = new ArrayList<>();
        for (Plan.Op op : ops) {
            out.add(ArgvSteps.step(op, verdictOf(verdicts, op.handle()), new ArgvSteps.Known(live, kept, ops)));
        }
        return out;
    }

    private static JsonNode verdictOf(List<JsonNode> verdicts, String handle) {
        for (JsonNode v : verdicts) {
            if (handle.equals(Json.text(v, "handle"))) {
                return v;
            }
        }
        return Json.object().nullNode();
    }

    /** One argv as a shell line, ids not yet made as {@code <handle>}. */
    public static String shellLine(List<Part> argv, Function<String, String> manifest) {
        List<String> words = new ArrayList<>();
        for (Part p : argv) {
            String word = switch (p.kind()) {
                case Part.MADE -> "<" + p.value() + ">";
                case Part.MANIFEST -> manifest.apply(p.value());
                default -> p.value();
            };
            words.add(quote(word));
        }
        return "lingara " + String.join(" ", words);
    }

    /** POSIX single quotes, only where a word needs them. */
    public static String quote(String word) {
        boolean plain = !word.isEmpty() && word.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || "-_./:@=,+".indexOf(c) >= 0));
        return plain ? word : "'" + word.replace("'", "'\\''") + "'";
    }
}
