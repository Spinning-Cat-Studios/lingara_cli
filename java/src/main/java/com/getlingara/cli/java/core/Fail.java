package com.getlingara.cli.java.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Why a command stopped (16.10.26ad D2): its exit, the code and text contract B2 prints, any
 * extra lines of advice, and what a part-way create already made. Thrown rather than returned,
 * so a handler reads as a plain sequence; {@code App.run} is the one place that catches it.
 */
public final class Fail extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Exit exit;
    private final String code;
    private String text;
    private final List<String> hints = new ArrayList<>();
    private final List<Made> made = new ArrayList<>();
    /** The refusing answer's body, for a command that re-words it. */
    private String body;

    public Fail(Exit exit, String code, String text) {
        // No stack trace: a Fail is an outcome, never a bug report.
        super(text, null, false, false);
        this.exit = exit;
        this.code = code;
        this.text = text;
    }

    public static Fail usage(String text) {
        return new Fail(Exit.USAGE, "usage", text);
    }

    public static Fail auth(String text) {
        return new Fail(Exit.AUTH_REQUIRED, "auth_required", text);
    }

    public static Fail failure(String text) {
        return new Fail(Exit.FAILURE, "failure", text);
    }

    /**
     * A refusal with a {@code messages.en.json} text: code and message id are the same word, as
     * every 16.10.26ae row's are. {@code vars} alternate name and value.
     */
    public static Fail message(Exit exit, String id, String... vars) {
        return new Fail(exit, id, Assets.message(id, vars));
    }

    public Fail hint(String line) {
        hints.add(line);
        return this;
    }

    public Exit exit() {
        return exit;
    }

    public String code() {
        return code;
    }

    public String text() {
        return text;
    }

    public void text(String replaced) {
        text = replaced;
    }

    public List<String> hints() {
        return hints;
    }

    public List<Made> made() {
        return made;
    }

    public String body() {
        return body;
    }

    public void body(String raw) {
        body = raw;
    }

    @Override
    public String getMessage() {
        return text;
    }
}
