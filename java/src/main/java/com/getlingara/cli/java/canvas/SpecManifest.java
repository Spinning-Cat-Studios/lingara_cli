package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.core.Json;

/**
 * {@code lingara.v1}'s {@code manifest()} over an app node's saved fields, in the order it writes
 * the members, with the defaults it fills; the listing's three members are written only when
 * their option is set.
 */
final class SpecManifest {
    private SpecManifest() {}

    static ObjectNode manifest(JsonNode o) {
        if (!o.isObject()) {
            throw new Spec.Invalid("manifest must be an object");
        }
        String locale = Json.isNull(o.path("defaultLocale")) ? "en" : Spec.text(o.path("defaultLocale"), "defaultLocale");
        ObjectNode m = Json.object();
        m.put("manifest_version", 1);
        m.put("default_locale", locale);
        m.set("name", Spec.localeText(o.path("name"), locale, "name"));
        m.set("description", Spec.localeText(o.path("description"), locale, "description"));
        m.put("render_url", Spec.text(o.path("renderUrl"), "renderUrl"));
        m.set("slots", Spec.texts(o.path("slots"), "slots"));
        m.set("context", orEmpty(Spec.optionalTexts(o.path("context"), "context")));
        m.set("scopes", orEmpty(Spec.optionalTexts(o.path("scopes"), "scopes")));
        m.put("tutor_note", o.path("tutorNote").isBoolean() && o.path("tutorNote").booleanValue());
        listing(o, locale, m);
        return m;
    }

    private static ArrayNode orEmpty(ArrayNode items) {
        return items == null ? Json.array() : items;
    }

    private static void listing(JsonNode o, String locale, ObjectNode m) {
        if (!Json.isNull(o.path("longDescription"))) {
            m.set("long_description", Spec.localeText(o.path("longDescription"), locale, "longDescription"));
        }
        JsonNode dev = o.path("developer");
        if (dev.isObject()) {
            ObjectNode d = Json.object();
            d.put("name", Spec.text(dev.path("name"), "developer.name"));
            if (dev.has("url")) {
                d.put("url", Spec.text(dev.get("url"), "url"));
            }
            if (dev.has("supportUrl")) {
                d.put("support_url", Spec.text(dev.get("supportUrl"), "supportUrl"));
            }
            m.set("developer", d);
        }
        JsonNode images = o.path("images");
        if (images.isArray()) {
            ArrayNode wire = Json.array();
            for (JsonNode i : images) {
                ObjectNode image = wire.addObject();
                image.put("sha256", Spec.text(i.path("sha256"), "images[].sha256"));
                image.put("role", Spec.text(i.path("role"), "images[].role"));
                image.set("alt", Spec.localeText(i.path("alt"), locale, "images[].alt"));
            }
            m.set("images", wire);
        }
    }
}
