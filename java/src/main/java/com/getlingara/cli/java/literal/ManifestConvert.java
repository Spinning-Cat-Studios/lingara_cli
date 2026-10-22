package com.getlingara.cli.java.literal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.core.Json;
import java.util.List;

/**
 * The closed key map from {@code lingara.v1}'s {@code ManifestOptions} to the wire manifest
 * (spec/connection-literal.md), in {@code lingara.manifest}'s member order. A key the map does
 * not name is refused, never passed through.
 */
final class ManifestConvert {
    /** Literal key, wire key, in the order the wire manifest writes them. */
    private static final String[][] KEYS = {
        {"defaultLocale", "default_locale"},
        {"name", "name"},
        {"description", "description"},
        {"renderUrl", "render_url"},
        {"slots", "slots"},
        {"context", "context"},
        {"scopes", "scopes"},
        {"tutorNote", "tutor_note"},
        {"longDescription", "long_description"},
        {"developer", "developer"},
        {"images", "images"},
    };
    private static final List<String> REQUIRED = List.of("name", "description", "renderUrl", "slots");
    private static final String[][] DEVELOPER = {{"name", "name"}, {"url", "url"}, {"supportUrl", "support_url"}};
    private static final String[][] IMAGE = {{"sha256", "sha256"}, {"role", "role"}, {"alt", "alt"}};

    private ManifestConvert() {}

    private static boolean known(String[][] map, String key) {
        for (String[] pair : map) {
            if (pair[0].equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static Lit find(List<Lit.Member> members, String key) {
        return Lit.obj(members, null).member(key);
    }

    /** A string is the default locale's; an object passes its tags through. */
    private static JsonNode localeMap(Lit lit, String locale) {
        if (lit.kind() == Lit.Kind.STR) {
            ObjectNode out = Json.object();
            out.put(locale, lit.text());
            return out;
        }
        return lit.toJson();
    }

    /** An object whose keys must all be in {@code map}, renamed through it. */
    private static JsonNode renamed(Lit lit, String[][] map, String locale) {
        if (lit.kind() != Lit.Kind.OBJ) {
            return lit.toJson();
        }
        ObjectNode out = Json.object();
        for (String[] pair : map) {
            Lit value = lit.member(pair[0]);
            if (value != null) {
                out.set(pair[1], pair[0].equals("alt") ? localeMap(value, locale) : value.toJson());
            }
        }
        for (Lit.Member m : lit.members()) {
            if (!known(map, m.key())) {
                throw Refusal.at("manifest_key", m.at());
            }
        }
        return out;
    }

    private static JsonNode images(Lit lit, String locale) {
        if (lit.kind() != Lit.Kind.ARR) {
            return lit.toJson();
        }
        ArrayNode out = Json.array();
        lit.items().forEach(i -> out.add(renamed(i, IMAGE, locale)));
        return out;
    }

    /** The wire manifest for the {@code manifest} object at {@code at}. */
    static JsonNode convert(List<Lit.Member> members, Lit.Pos at) {
        for (Lit.Member m : members) {
            if (!known(KEYS, m.key())) {
                throw Refusal.at("manifest_key", m.at());
            }
        }
        if (REQUIRED.stream().anyMatch(k -> find(members, k) == null)) {
            throw Refusal.at("manifest_key", at);
        }
        Lit defaultLocale = find(members, "defaultLocale");
        String locale = defaultLocale != null && defaultLocale.kind() == Lit.Kind.STR ? defaultLocale.text() : "en";
        ObjectNode out = Json.object();
        out.put("manifest_version", 1);
        out.set("default_locale", defaultLocale == null ? Json.object().textNode("en") : defaultLocale.toJson());
        for (int k = 1; k < KEYS.length; k++) {
            JsonNode value = member(find(members, KEYS[k][0]), KEYS[k][0], locale);
            if (value != null) {
                out.set(KEYS[k][1], value);
            }
        }
        return out;
    }

    /** One member's wire value, its default when absent, or {@code null} to leave it out. */
    private static JsonNode member(Lit value, String key, String locale) {
        if (value == null) {
            return switch (key) {
                case "context", "scopes" -> Json.array();
                case "tutorNote" -> Json.object().booleanNode(false);
                default -> null;
            };
        }
        return switch (key) {
            case "name", "description", "longDescription" -> localeMap(value, locale);
            case "developer" -> renamed(value, DEVELOPER, locale);
            case "images" -> images(value, locale);
            default -> value.toJson();
        };
    }
}
