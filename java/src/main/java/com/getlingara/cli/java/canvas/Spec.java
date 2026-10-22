package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.core.Json;
import java.util.Iterator;
import java.util.Map;

/**
 * A field node's op body: the web's {@code specFromFields} then {@code specBody} ({@code
 * testRun.ts}, {@code plan.ts}), over the node's saved fields. A create's client is always {@code
 * billing_mode: allowance}; no body carries {@code enabled}, {@code api_version} or {@code
 * client_type}. The app manifest is {@code lingara.v1}'s {@code manifest()} wire form, with the
 * defaults it fills. Each refusal is an {@link Invalid} naming what is wrong.
 */
public final class Spec {
    private Spec() {}

    /** Why a node's fields are not its kind's spec. */
    public static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String why) {
            super(why, null, false, false);
        }
    }

    static String text(JsonNode v, String what) {
        if (!v.isTextual()) {
            throw new Invalid(what + " must be a string");
        }
        return v.asText();
    }

    static ArrayNode texts(JsonNode v, String what) {
        if (!v.isArray()) {
            throw new Invalid(what + " must be an array of strings");
        }
        ArrayNode out = Json.array();
        v.forEach(item -> out.add(text(item, what)));
        return out;
    }

    /** {@code null} for absent or null; else {@link #texts}. */
    static ArrayNode optionalTexts(JsonNode v, String what) {
        return Json.isNull(v) ? null : texts(v, what);
    }

    /** A plain string is {@code {locale: s}}; a map must hold strings only. */
    static JsonNode localeText(JsonNode v, String locale, String what) {
        if (v.isTextual()) {
            ObjectNode out = Json.object();
            out.put(locale, v.asText());
            return out;
        }
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                if (!it.next().getValue().isTextual()) {
                    throw new Invalid(what + " must be a string or an object of strings");
                }
            }
            return v.deepCopy();
        }
        throw new Invalid(what + " must be a string or an object of strings");
    }

    /** {@code {name, scopes, redirect_uris?}} plus {@code billing_mode} on a create. */
    public static ObjectNode client(JsonNode fields, boolean create) {
        ObjectNode body = Json.object();
        body.put("name", text(fields.path("name"), "name"));
        body.set("scopes", texts(fields.path("scopes"), "scopes"));
        ArrayNode uris = optionalTexts(fields.path("redirectUris"), "redirectUris");
        if (uris != null) {
            body.set("redirect_uris", uris);
        }
        if (create) {
            body.put("billing_mode", "allowance");
        }
        return body;
    }

    public static ObjectNode webhook(JsonNode fields) {
        ObjectNode body = Json.object();
        body.put("url", text(fields.path("url"), "url"));
        body.set("event_types", texts(fields.path("events"), "events"));
        return body;
    }

    public static ObjectNode app(JsonNode fields) {
        ObjectNode body = Json.object();
        body.set("manifest", SpecManifest.manifest(fields.path("manifest")));
        return body;
    }

    /** The name an app op registers under: the manifest's in its default locale. */
    public static String appName(JsonNode body, String fallback) {
        JsonNode manifest = body.path("manifest");
        String locale = Json.text(manifest, "default_locale", "en");
        JsonNode names = manifest.path("name");
        String name = Json.text(names, locale);
        if (name == null && names.isObject()) {
            for (JsonNode v : names) {
                if (v.isTextual()) {
                    return v.asText();
                }
            }
        }
        return name != null ? name : fallback;
    }
}
