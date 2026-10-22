package com.getlingara.cli.java.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Pair;
import java.util.List;
import java.util.Map;

/**
 * One request before it is spelled: a route, the values its {@code {name}}s take, a query in
 * order, and a body: none, JSON, or a form.
 */
public record Request(Route route, Map<String, String> vars, List<Pair> query, JsonNode json, List<Pair> form) {
    public static Request of(Route route) {
        return new Request(route, Map.of(), List.of(), null, null);
    }

    public static Request of(Route route, Map<String, String> vars) {
        return new Request(route, vars, List.of(), null, null);
    }

    public Request withVars(Map<String, String> filled) {
        return new Request(route, filled, query, json, form);
    }

    public Request withQuery(List<Pair> pairs) {
        return new Request(route, vars, pairs, json, form);
    }

    public Request withJson(JsonNode body) {
        return new Request(route, vars, query, body, null);
    }

    public Request withForm(List<Pair> fields) {
        return new Request(route, vars, query, null, fields);
    }
}
