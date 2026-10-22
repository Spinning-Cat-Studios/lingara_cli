package com.getlingara.cli.java.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.IntPredicate;

/**
 * The process's own terminal (ADR 16.10.26af D2): {@code isatty(0) && isatty(2)} through JNA,
 * which java-keyring already puts on the classpath.
 *
 * <p>Never {@code System.console()}: on JDK 17 it is null whenever stdout is a pipe, which every
 * conformance {@code tty: true} case makes it (16.10.26ac D5 step 4), and a prompt must still
 * appear when stdout is redirected. The descriptor test is injectable so a unit test can see
 * which descriptors it reads (16.10.26af AC17).
 */
public final class StdioTerminal implements Terminal {
    static final int STDIN = 0;
    static final int STDERR = 2;

    private final IntPredicate isatty;
    private BufferedReader stdin;

    /** libc's {@code isatty(3)}, loaded on the first prompt only. */
    interface LibC extends Library {
        int isatty(int fd);
    }

    private static final class Loaded {
        static final LibC C = Native.load("c", LibC.class);
    }

    public StdioTerminal() {
        this(fd -> Loaded.C.isatty(fd) == 1);
    }

    public StdioTerminal(IntPredicate isatty) {
        this.isatty = isatty;
    }

    @Override
    public boolean interactive() {
        return isatty.test(STDIN) && isatty.test(STDERR);
    }

    @Override
    public String readLine() {
        try {
            if (stdin == null) {
                stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            String line = stdin.readLine();
            return line == null ? null : line + "\n";
        } catch (IOException e) {
            return null;
        }
    }
}
