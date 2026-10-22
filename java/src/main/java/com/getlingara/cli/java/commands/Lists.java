package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.core.Table;
import com.getlingara.cli.java.http.Request;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists: every page a cursor reaches, and a list's human columns, each a heading and the member it
 * reads, as {@code {"ID", "id", "NAME", "name", …}}, so a column list stays one line of data
 * (16.10.26ad D9).
 */
public final class Lists {
    private Lists() {}

    /** Every page of a cursor-paged route, followed by {@code next_cursor} (B1). */
    public static List<JsonNode> pages(Ctx ctx, Request first, List<Pair> fixedQuery) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        do {
            List<Pair> query = new ArrayList<>(fixedQuery);
            if (cursor != null) {
                query.add(new Pair("cursor", cursor));
            }
            JsonNode page = Calls.call(ctx, first.withQuery(query));
            page.path("items").forEach(items::add);
            cursor = Json.text(page, "next_cursor");
        } while (cursor != null);
        return items;
    }

    /** Every page of {@code k}, printed: one JSON array, or the schema's columns. */
    public static void printList(Ctx ctx, Kind k, String... columns) {
        printRows(ctx, Objects.list(ctx, k), columns);
    }

    public static void printRows(Ctx ctx, List<JsonNode> items, String... columns) {
        List<String> headings = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i + 1 < columns.length; i += 2) {
            headings.add(columns[i]);
            keys.add(columns[i + 1]);
        }
        ctx.out.list(items, headings, item -> {
            List<String> cells = new ArrayList<>();
            keys.forEach(key -> cells.add(Table.cell(item.path(key))));
            return cells;
        });
    }
}
