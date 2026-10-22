package com.getlingara.cli.java.core;

/**
 * The terminal the ritual talks to (16.10.26ad D5 step 1): interactive means stdin
 * <strong>and</strong> stderr are terminals, and prompts go to stderr, so {@code lingara apps
 * delete x > out.json} still prompts.
 */
public interface Terminal {
    boolean interactive();

    /** One line from stdin, {@code null} at end of input. */
    String readLine();
}
