package com.getlingara.cli.java.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Signal;

/**
 * Ctrl+C and SIGTERM (ADR 16.10.26af D9): trapped, never left to kill the JVM. A command that
 * waits ({@code apps dev}, {@code login}'s loopback) watches the flag, cleans up and
 * <strong>returns</strong> {@code interrupted} (130). Outside a watch there is nothing to clean
 * up, so the signal ends the run with exit status 130 at once: an exit, never a death by signal.
 *
 * <p>{@code sun.misc.Signal} is the JDK's one way to trap a signal; it lives in {@code
 * jdk.unsupported}, which every JDK since 9 exports.
 */
public final class Interrupts {
    private static final AtomicBoolean RAISED = new AtomicBoolean();
    private static final AtomicInteger WATCHERS = new AtomicInteger();

    private Interrupts() {}

    /** Traps SIGINT and SIGTERM for the rest of the run. */
    public static void install() {
        for (String name : new String[] {"INT", "TERM"}) {
            try {
                Signal.handle(new Signal(name), signal -> raise());
            } catch (IllegalArgumentException e) {
                // A platform without this signal (TERM on some Windows JVMs) keeps its default.
            }
        }
    }

    /** What a trapped signal does; public so a test can raise one without a real signal. */
    public static void raise() {
        RAISED.set(true);
        if (WATCHERS.get() == 0) {
            System.exit(Exit.INTERRUPTED.code());
        }
    }

    /** Whether a signal arrived during the current watch. */
    public static boolean raised() {
        return RAISED.get();
    }

    /** One watch: a signal now sets the flag instead of ending the run. */
    public static Watch watch() {
        if (WATCHERS.getAndIncrement() == 0) {
            RAISED.set(false);
        }
        return new Watch();
    }

    /** Closed when the waiting command is done waiting. */
    public static final class Watch implements AutoCloseable {
        private boolean open = true;

        private Watch() {}

        @Override
        public void close() {
            if (open) {
                open = false;
                WATCHERS.decrementAndGet();
            }
        }
    }
}
