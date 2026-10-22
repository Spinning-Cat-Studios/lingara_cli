package com.getlingara.cli.java.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 16.10.26ae D4's two git questions: is a file tracked (a warning), and does git ignore a path
 * that would hold secrets (a refusal). Outside a work tree, or with no {@code git} on {@code
 * PATH}, there is no index to commit a file into, so both answers are the permissive ones and the
 * checks are skipped.
 */
public final class Git {
    private Git() {}

    /** {@code git -C <dir> <args>}, quietly; {@code null} when git could not run. */
    private static Boolean git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice())))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    private static Path dirOf(Path path) {
        Path parent = path.getParent();
        return parent == null || parent.toString().isEmpty() ? Path.of(".") : parent;
    }

    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    /** Whether {@code dir} is inside a git work tree, with git available. */
    public static boolean inWorkTree(Path dir) {
        return Boolean.TRUE.equals(git(dir, "rev-parse", "--is-inside-work-tree"));
    }

    /** Whether git tracks {@code path}; false outside a work tree. */
    public static boolean tracked(Path path) {
        Path dir = dirOf(path);
        return inWorkTree(dir) && Boolean.TRUE.equals(git(dir, "ls-files", "--error-unmatch", "--", nameOf(path)));
    }

    /** Whether git ignores {@code path}; true outside a work tree, where nothing can be committed. */
    public static boolean ignored(Path path) {
        Path dir = dirOf(path);
        return !inWorkTree(dir) || Boolean.TRUE.equals(git(dir, "check-ignore", "-q", "--", nameOf(path)));
    }
}
