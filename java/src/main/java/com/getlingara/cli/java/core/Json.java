package com.getlingara.cli.java.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Every JSON value is a Jackson tree. Its object nodes keep insertion order, which is the order
 * the server wrote and the order B1's {@code FIELD  VALUE} table prints (the reference kit's
 * {@code preserve_order}).
 */
public final class Json {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Json() {}

    /** Built on first use, so a run that prints no JSON never pays for the mapper. */
    private static final class Holder {
        static final ObjectMapper MAPPER = new ObjectMapper();
    }

    public static ObjectMapper mapper() {
        return Holder.MAPPER;
    }

    public static ObjectNode object() {
        return NODES.objectNode();
    }

    public static ArrayNode array() {
        return NODES.arrayNode();
    }

    public static ArrayNode array(Collection<String> items) {
        ArrayNode out = array();
        items.forEach(out::add);
        return out;
    }

    /** {@code text} as JSON, or {@code null} when it is not JSON. */
    public static JsonNode parse(String text) {
        try {
            JsonNode node = mapper().readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonNode parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Why {@code text} is not JSON, or {@code null} when it is. */
    public static String parseError(String text) {
        try {
            mapper().readTree(text);
            return null;
        } catch (JsonProcessingException e) {
            return e.getOriginalMessage();
        }
    }

    /** Compact JSON, as one line. */
    public static String write(JsonNode node) {
        try {
            return mapper().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    /** {@code node[key]} when it is a string; {@code null} otherwise. */
    public static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** {@code node[key]} when it is a string; {@code fallback} otherwise. */
    public static String text(JsonNode node, String key, String fallback) {
        String value = text(node, key);
        return value == null ? fallback : value;
    }

    /** {@code node[key]}, never {@code null}: a missing member is a missing node. */
    public static JsonNode at(JsonNode node, String key) {
        return node == null ? NODES.missingNode() : node.path(key);
    }

    /** The strings of an array; anything else is none. */
    public static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    out.add(item.asText());
                }
            });
        }
        return out;
    }

    /** JSON {@code null} or absent. */
    public static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
