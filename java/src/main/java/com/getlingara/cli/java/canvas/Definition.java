package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * A canvas definition (Backend 2.10.26e §2), as far as apply reads it: nodes with their kind,
 * fields and whether a script runs them, the edges, and the order the web's {@code
 * flowDefinition.ts} walks them in.
 */
public record Definition(List<Node> nodes, List<String[]> edges) {
    /** One node; {@code custom} is a script node, which runs only in the browser's sandbox (2.10.26b D5). */
    public record Node(String id, String kind, JsonNode fields, boolean custom) {}

    public static Definition read(JsonNode value) {
        List<Node> nodes = new ArrayList<>();
        for (JsonNode n : value.path("nodes")) {
            boolean custom = n.path("custom").asBoolean(false) && n.path("custom").isBoolean() || n.path("script").isTextual();
            nodes.add(new Node(Json.text(n, "id", ""), Json.text(n, "kind", ""), n.path("fields"), custom));
        }
        List<String[]> edges = new ArrayList<>();
        for (JsonNode e : value.path("edges")) {
            edges.add(new String[] {Json.text(e, "from", ""), Json.text(e, "to", "")});
        }
        return new Definition(nodes, edges);
    }

    /** Kahn's algorithm, ties broken by node order, as {@code topoOrder}; {@code null} on a cycle. */
    public List<Node> topoOrder() {
        int[] indegree = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            for (String[] edge : edges) {
                if (edge[1].equals(nodes.get(i).id())) {
                    indegree[i]++;
                }
            }
        }
        boolean[] done = new boolean[nodes.size()];
        List<Node> order = new ArrayList<>();
        while (order.size() < nodes.size()) {
            int next = nextReady(indegree, done);
            if (next < 0) {
                return null;
            }
            done[next] = true;
            order.add(nodes.get(next));
            release(nodes.get(next).id(), indegree);
        }
        return order;
    }

    private static int nextReady(int[] indegree, boolean[] done) {
        for (int i = 0; i < indegree.length; i++) {
            if (!done[i] && indegree[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private void release(String from, int[] indegree) {
        for (String[] edge : edges) {
            if (!edge[0].equals(from)) {
                continue;
            }
            for (int j = 0; j < nodes.size(); j++) {
                if (nodes.get(j).id().equals(edge[1])) {
                    indegree[j] = Math.max(0, indegree[j] - 1);
                    break;
                }
            }
        }
    }

    /**
     * A dependent node's client: its one direct upstream {@code client} node, or {@code null} for
     * zero or two ({@code clientHandleOf}).
     */
    public String clientOf(String handle) {
        List<String> parents = new ArrayList<>();
        for (Node n : nodes) {
            if (n.kind().equals("client") && feeds(n.id(), handle)) {
                parents.add(n.id());
            }
        }
        return parents.size() == 1 ? parents.get(0) : null;
    }

    private boolean feeds(String from, String to) {
        for (String[] edge : edges) {
            if (edge[0].equals(from) && edge[1].equals(to)) {
                return true;
            }
        }
        return false;
    }
}
