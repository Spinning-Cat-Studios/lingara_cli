package com.getlingara.cli.java.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contract B1's human table, rendered here rather than by a table library, because those draw
 * borders and wrap cells, which B1 forbids (16.10.26ad D6).
 */
public final class Table {
    private Table() {}

    /**
     * A header row and the rows under it: left-aligned, padded to the widest cell, two spaces
     * apart, no trailing whitespace, never truncated.
     */
    public static String render(List<String> columns, List<List<String>> rows) {
        List<List<String>> all = new ArrayList<>();
        all.add(columns);
        all.addAll(rows);
        int[] widths = new int[columns.size()];
        for (List<String> row : all) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], length(cellText(i < row.size() ? row.get(i) : "")));
            }
        }
        StringBuilder out = new StringBuilder();
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < widths.length; i++) {
                String text = cellText(i < row.size() ? row.get(i) : "");
                line.append(i == 0 ? "" : "  ").append(text).append(" ".repeat(widths[i] - length(text)));
            }
            out.append(line.toString().stripTrailing()).append('\n');
        }
        return out.toString();
    }

    /** Width in characters, as a terminal counts them, not UTF-16 units. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** An empty value is {@code -}, so no cell is blank and the split stays exact. */
    private static String cellText(String cell) {
        return cell == null || cell.isEmpty() ? "-" : cell;
    }

    /**
     * One JSON value as a cell: a string as itself, a list of scalars joined with commas, {@code
     * null} and an empty list as {@code -}, anything else compact JSON.
     */
    public static String cell(JsonNode value) {
        if (Json.isNull(value)) {
            return "-";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray() && value.isEmpty()) {
            return "-";
        }
        if (value.isArray() && scalars(value)) {
            List<String> parts = new ArrayList<>();
            value.forEach(item -> parts.add(cell(item)));
            return String.join(",", parts);
        }
        return Json.write(value);
    }

    private static boolean scalars(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    /** A {@code get}: B1's two-column {@code FIELD  VALUE} form, one row per member. */
    public static List<List<String>> fields(JsonNode object) {
        List<List<String>> rows = new ArrayList<>();
        if (object == null || !object.isObject()) {
            rows.add(List.of("value", cell(object)));
            return rows;
        }
        for (Map.Entry<String, JsonNode> member : object.properties()) {
            rows.add(List.of(member.getKey(), cell(member.getValue())));
        }
        return rows;
    }
}
