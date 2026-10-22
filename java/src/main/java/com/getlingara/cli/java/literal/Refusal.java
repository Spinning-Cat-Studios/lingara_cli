package com.getlingara.cli.java.literal;

import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import java.util.Map;

/**
 * Why a connection file is refused (16.10.26ae D2): a code at a position, or a scripted statement
 * named by its handle ({@code code} is then {@code connection_scripted}).
 */
public final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final String code;
    public final Lit.Pos at;
    public final String statement;

    private static final Map<String, String> WHAT = Map.ofEntries(
            Map.entry("header", "the first line must be `// lingara connection v1 (schema 2)`"),
            Map.entry("comment", "comments go inside a script; the builder rewrites everything outside them"),
            Map.entry("text", "write text in quotes, with only the escapes JSON knows, and no raw tab or line break"),
            Map.entry("number", "write finite numbers in decimal, as JSON does"),
            Map.entry("key", "write each key once"),
            Map.entry("handle", "this handle is already declared"),
            Map.entry("reference", "this names no statement declared above it"),
            Map.entry("syntax", "not part of the literal subset"),
            Map.entry("app_count", "the file needs exactly one lingara.app statement"),
            Map.entry("option", "an app takes only client and manifest"),
            Map.entry("manifest_key", "not a manifest key lingara reads, or a required one is missing"));

    private Refusal(String code, Lit.Pos at, String statement) {
        super(code, null, false, false);
        this.code = code;
        this.at = at;
        this.statement = statement;
    }

    public static Refusal at(String code, Lit.Pos at) {
        return new Refusal(code, at, null);
    }

    public static Refusal scripted(String handle) {
        return new Refusal("connection_scripted", null, handle);
    }

    /** As B2 prints it: {@code connection_scripted}, else {@code usage} with {@code <file>:<line>:<column>: <what>}. */
    public Fail fail(String file) {
        if (statement != null) {
            return Fail.message(Exit.CONNECTION_SCRIPTED, "connection_scripted", "file", file, "statement", statement);
        }
        String what = WHAT.getOrDefault(code, "not part of the literal subset");
        return new Fail(Exit.USAGE, "connection_literal", file + ":" + at.line() + ":" + at.column() + ": " + what + " (" + code + ")");
    }
}
