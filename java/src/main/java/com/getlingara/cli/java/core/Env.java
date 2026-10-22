package com.getlingara.cli.java.core;

import java.util.Map;
import java.util.TreeMap;

/** The process environment, read once, so tests pass their own. */
public final class Env {
    private final Map<String, String> vars;

    public Env(Map<String, String> vars) {
        this.vars = new TreeMap<>(vars);
    }

    public static Env fromProcess() {
        return new Env(System.getenv());
    }

    /** A set, non-empty variable; {@code null} otherwise. */
    public String get(String name) {
        String value = vars.get(name);
        return value == null || value.isEmpty() ? null : value;
    }

    /** Every variable, sorted by name, as the app's environment is built from. */
    public Map<String, String> all() {
        return vars;
    }
}
