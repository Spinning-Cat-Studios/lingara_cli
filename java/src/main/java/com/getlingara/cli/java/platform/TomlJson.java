package com.getlingara.cli.java.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * A parsed TOML document as the JSON its schema is written over ({@code
 * spec/platform-toml.schema.json} validates "the JSON its TOML parses to"). Tables keep file
 * order, so the first secret-shaped key is the first in the file, as every kit reports it
 * (16.10.26ae D4); an integer that fits is an {@code int} node, so {@code const: 1} compares
 * equal; a date or time is its TOML text.
 */
public final class TomlJson {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private TomlJson() {}

    public static ObjectNode table(TomlTable table) {
        ObjectNode out = NODES.objectNode();
        for (String key : table.keySet()) {
            out.set(key, value(table.get(List.of(key))));
        }
        return out;
    }

    private static ArrayNode array(TomlArray array) {
        ArrayNode out = NODES.arrayNode();
        for (int i = 0; i < array.size(); i++) {
            out.add(value(array.get(i)));
        }
        return out;
    }

    private static JsonNode value(Object v) {
        if (v instanceof TomlTable t) {
            return table(t);
        }
        if (v instanceof TomlArray a) {
            return array(a);
        }
        if (v instanceof Long n) {
            return n == n.intValue() ? NODES.numberNode(n.intValue()) : NODES.numberNode(n);
        }
        if (v instanceof Double d) {
            return NODES.numberNode(d);
        }
        if (v instanceof Boolean b) {
            return NODES.booleanNode(b);
        }
        return v instanceof String s ? NODES.textNode(s) : NODES.textNode(String.valueOf(v));
    }
}
