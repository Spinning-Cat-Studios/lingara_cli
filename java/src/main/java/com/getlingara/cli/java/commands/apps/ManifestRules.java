package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the CLI checks in a wire manifest before it sends one (ADR 16.10.26ae D6): the ephemeral
 * render URL a version may never carry, the manifest rules a warning depends on, and the warnings
 * themselves, pinned by {@code spec/manifest-warnings.json} (the app kits' {@code
 * manifestWarnings}). The server stays the authority on every rule; these run first so a refusal
 * costs no request.
 */
public final class ManifestRules {
    /** The slots a manifest may name (the app view's {@code AppSlotName}). */
    private static final Set<String> SLOTS = Set.of("home.side", "plans.empty_detail");

    /** The text each warning prints with, as {@code manifest_warning}'s {@code {text}}. */
    private static final String SINGLE_LOCALE = "the listing is in its default locale only; give name and description a second locale";

    private ManifestRules() {}

    /** The URL's host, lowercased: no scheme, user, port or path. */
    static String host(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        String authority = rest.split("[/?#]", -1)[0];
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        return host.split(":", -1)[0].toLowerCase(Locale.ROOT);
    }

    /** A quick tunnel's or the template's placeholder: never a version's URL. */
    public static boolean ephemeral(String renderUrl) {
        String host = host(renderUrl);
        return host.equals("dev.invalid") || host.equals("trycloudflare.com") || host.endsWith(".trycloudflare.com");
    }

    private static boolean localeMap(JsonNode v) {
        if (!v.isObject() || v.isEmpty()) {
            return false;
        }
        for (JsonNode s : v) {
            if (!s.isTextual() || s.asText().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDuplicate(JsonNode v) {
        Set<JsonNode> seen = new HashSet<>();
        for (JsonNode item : v.isArray() ? v : Json.array()) {
            if (!seen.add(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean all(JsonNode array, Predicate<JsonNode> keep) {
        for (JsonNode item : array) {
            if (!keep.test(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean defaultLocaleOk(JsonNode m, String locale) {
        Predicate<String> inMap = key -> !m.path(key).isObject() || m.path(key).has(locale);
        return !locale.isEmpty() && inMap.test("name") && inMap.test("description");
    }

    private static boolean renderUrlOk(String url) {
        return url != null && url.length() > 8 && url.substring(0, 8).equalsIgnoreCase("https://") && !host(url).isEmpty();
    }

    private static boolean slotsOk(JsonNode slots) {
        return slots.isArray() && !slots.isEmpty() && all(slots, s -> s.isTextual() && SLOTS.contains(s.asText()));
    }

    private static boolean contextOk(JsonNode context) {
        return context == null || context.isArray() && all(context, JsonNode::isTextual);
    }

    private static boolean noDuplicates(JsonNode m) {
        return !hasDuplicate(m.path("slots")) && !hasDuplicate(m.path("context")) && !hasDuplicate(m.path("scopes"));
    }

    /** The first rule {@code m} breaks, of the ones a warning depends on, in the app kits' order. */
    public static String brokenRule(JsonNode m) {
        String locale = Json.text(m, "default_locale", "");
        JsonNode version = m.path("manifest_version");
        boolean[] kept = {
            version.isIntegralNumber() && version.asLong() == 1,
            defaultLocaleOk(m, locale),
            localeMap(m.path("name")),
            localeMap(m.path("description")),
            renderUrlOk(Json.text(m, "render_url")),
            slotsOk(m.path("slots")),
            contextOk(m.get("context")),
            !m.has("locales"),
            noDuplicates(m),
        };
        String[] rules = {"manifest_version", "default_locale", "name", "description", "render_url", "slots", "context", "locales", "duplicate"};
        for (int i = 0; i < rules.length; i++) {
            if (!kept[i]) {
                return rules[i];
            }
        }
        return null;
    }

    private static List<String> keys(JsonNode m, String key) {
        List<String> out = new ArrayList<>();
        m.path(key).fieldNames().forEachRemaining(out::add);
        return out;
    }

    /**
     * Where {@code name} and {@code description} both have an entry, and {@code long_description}
     * too when present and not null, in byte order; empty for a manifest the rules refuse.
     */
    public static List<String> locales(JsonNode m) {
        List<String> out = new ArrayList<>();
        if (brokenRule(m) != null) {
            return out;
        }
        boolean longNull = Json.isNull(m.path("long_description"));
        for (String l : keys(m, "name")) {
            if (keys(m, "description").contains(l) && (longNull || keys(m, "long_description").contains(l))) {
                out.add(l);
            }
        }
        out.sort(null);
        return out;
    }

    /** The listing's warnings: v1 {@code single_locale} alone. Never a refusal. */
    public static List<String> warnings(JsonNode m) {
        List<String> locales = locales(m);
        boolean onlyDefault = locales.size() == 1 && locales.get(0).equals(Json.text(m, "default_locale"));
        return onlyDefault ? List.of("single_locale") : List.of();
    }

    public static String warningText(String code) {
        return code.equals("single_locale") ? SINGLE_LOCALE : "";
    }
}
