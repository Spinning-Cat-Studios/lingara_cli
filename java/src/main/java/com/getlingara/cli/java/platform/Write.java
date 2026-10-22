package com.getlingara.cli.java.platform;

import com.getlingara.cli.java.core.Fail;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writing {@code platform.toml}, its example and {@code .gitignore}, byte for byte as {@code
 * spec/templates.md} says, so every kit leaves the same files behind. The edits are line-based on
 * purpose: a developer's comments and layout outside the touched row survive.
 */
public final class Write {
    public static final String FILE_HEADER = "# platform.toml — gitignored; copy from platform.toml.example.\n"
            + "# Never put a secret here. lingara refuses to read a value that looks like one.\n"
            + "schema = 1\n";
    public static final String EXAMPLE_HEADER = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n"
            + "# then run lingara apps update to register your own private copy of each app.\n"
            + "schema = 1\n";

    private Write() {}

    /** A row as {@code create} and {@code pin} write it; {@code manifest} is {key, value} or {@code null}. */
    public record NewRow(String name, String path, int port, String start, String[] manifest) {}

    /** A TOML basic string. */
    public static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String rowText(NewRow row, String id) {
        StringBuilder out = new StringBuilder("\n[[apps]]\nname = " + quote(row.name()) + "\n");
        if (id != null) {
            out.append("id = ").append(quote(id)).append('\n');
        }
        out.append("path = ").append(quote(row.path())).append("\nport = ").append(row.port()).append('\n');
        if (row.start() != null) {
            out.append("start = ").append(quote(row.start())).append('\n');
        }
        if (row.manifest() != null) {
            out.append(row.manifest()[0]).append(" = ").append(quote(row.manifest()[1])).append('\n');
        }
        return out.toString();
    }

    static Fail io(Path path, IOException e) {
        return Fail.failure(path + ": " + e.getMessage());
    }

    static String readOr(Path path, String header) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return !text.isEmpty() && !text.endsWith("\n") ? text + "\n" : text;
        } catch (NoSuchFileException e) {
            return header;
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    /** Lines as Rust's {@code str::lines} gives them: split on LF, a final empty piece dropped, CR trimmed. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        out.replaceAll(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        return out;
    }

    /** Whether {@code text} holds a row named {@code name}. */
    public static boolean hasRow(String text, String name) {
        return RowSpans.spanOf(lines(text), name) != null;
    }

    /**
     * Appends the row to {@code file} (with {@code id}, if any) and, when the example beside it has
     * no row of that name, to the example (with the placeholder).
     */
    public static void pinRow(Path file, NewRow row, String id) {
        write(file, readOr(file, FILE_HEADER) + rowText(row, id));
        Path example = file.resolveSibling(PlatformToml.EXAMPLE);
        String current = readOr(example, EXAMPLE_HEADER);
        if (!hasRow(current, row.name())) {
            write(example, current + rowText(row, AppRow.PLACEHOLDER_ID));
        }
    }

    /** Writes {@code id} into the row named {@code name}: replaces its {@code id} line, or adds one after its {@code name} line. */
    public static void setId(Path file, String name, String id) {
        List<String> lines;
        try {
            lines = lines(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw io(file, e);
        }
        int[] span = RowSpans.spanOf(lines, name);
        if (span == null) {
            return;
        }
        String idLine = "id = " + quote(id);
        int found = RowSpans.find(lines, span, "id ", "id=");
        if (found >= 0) {
            lines.set(found, idLine);
        } else {
            int after = RowSpans.find(lines, span, "name");
            lines.add((after >= 0 ? after : span[0]) + 1, idLine);
        }
        write(file, RowSpans.joined(lines));
    }

    /** Removes the row named {@code name}; {@code false} when there is none. */
    public static boolean removeRow(Path file, String name) {
        List<String> lines;
        try {
            lines = lines(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw io(file, e);
        }
        int[] span = RowSpans.spanOf(lines, name);
        if (span == null) {
            return false;
        }
        int start = span[0];
        if (span[1] == lines.size()) {
            while (start > 0 && lines.get(start - 1).isBlank()) {
                start--;
            }
        }
        List<String> kept = new ArrayList<>(lines.subList(0, start));
        kept.addAll(lines.subList(span[1], lines.size()));
        write(file, RowSpans.joined(kept));
        return true;
    }

    /** Adds a {@code platform.toml} line to {@code <dir>/.gitignore} when no line is one. */
    public static void ensureGitignore(Path dir) {
        Path path = dir.resolve(".gitignore");
        String text = readOr(path, "");
        if (lines(text).stream().anyMatch(l -> l.strip().equals(PlatformToml.FILE))) {
            return;
        }
        write(path, text + PlatformToml.FILE + "\n");
    }
}
