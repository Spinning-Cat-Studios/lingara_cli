package com.getlingara.cli.java.supervise;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A running child and every process under it (ADR 16.10.26af D2: {@code ProcessBuilder}, and
 * {@code ProcessHandle.descendants()} for tree shutdown). The JVM cannot start a child in a
 * process group of its own, so stopping walks the tree instead: the descendants are snapshotted
 * <em>before</em> anything is signalled (a dead parent's children are re-parented and fall out of
 * the tree), all get SIGTERM, and whatever outlives the grace period gets SIGKILL. On Windows the
 * same walk ends the tree, as {@code taskkill /T} would.
 */
public final class Group {
    private final Process process;

    private Group(Process process) {
        this.process = process;
    }

    public static Group spawn(ProcessBuilder command) throws IOException {
        return new Group(command.start());
    }

    public Process process() {
        return process;
    }

    public long pid() {
        return process.pid();
    }

    /** The exit status, if it has exited; {@code null} while it runs. */
    public Integer exited() {
        return process.isAlive() ? null : process.exitValue();
    }

    /** The whole tree: SIGTERM, then SIGKILL once {@code graceMs} passes. */
    public void stop(long graceMs) {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(0, process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.currentTimeMillis() + graceMs;
        while (tree.stream().anyMatch(ProcessHandle::isAlive) && System.currentTimeMillis() < deadline) {
            Shell.sleep(50);
        }
        // A grandchild may have been forked during the grace period: take a second snapshot.
        process.descendants().forEach(tree::add);
        tree.forEach(ProcessHandle::destroyForcibly);
        try {
            process.waitFor(graceMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
