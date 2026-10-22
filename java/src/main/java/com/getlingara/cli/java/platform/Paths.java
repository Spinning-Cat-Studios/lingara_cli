package com.getlingara.cli.java.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** How every message names a path, and the one canonical form paths are compared in. */
public final class Paths {
    private Paths() {}

    /** {@code path} relative to {@code base} when it is under it (component-wise), else whole. */
    public static String shown(Path path, Path base) {
        return path.startsWith(base) ? base.relativize(path).toString() : path.toString();
    }

    /** Through any symlink, so a row's path compares; as given when it does not exist. */
    public static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /** {@code dir} relative to {@code base}, {@code /}-separated; {@code .} when they are one. */
    public static String relative(Path dir, Path base) {
        if (!dir.startsWith(base)) {
            return dir.toString();
        }
        Path rel = base.relativize(dir);
        if (rel.toString().isEmpty()) {
            return ".";
        }
        List<String> parts = new ArrayList<>();
        rel.forEach(p -> parts.add(p.toString()));
        return String.join("/", parts);
    }
}
