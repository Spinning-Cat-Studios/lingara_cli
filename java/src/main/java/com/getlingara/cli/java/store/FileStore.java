package com.getlingara.cli.java.store;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@code <config dir>/credentials.json}, mode {@code 0600} in a {@code 0700} directory, shaped
 * {@code {"schema": 1, "profiles": {"<profile>": <record>}}} (16.10.26ad D4).
 */
public final class FileStore implements CredentialStore {
    private final Path path;

    public FileStore(Path dir) {
        this.path = dir.resolve("credentials.json");
    }

    public Path path() {
        return path;
    }

    private ObjectNode read() throws StoreException {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            ObjectNode empty = Json.object();
            empty.put("schema", 1);
            empty.putObject("profiles");
            return empty;
        } catch (IOException e) {
            throw StoreException.broken(path + ": " + e.getMessage());
        }
        JsonNode file = Json.parse(text);
        if (file == null || !file.isObject() || !file.path("schema").canConvertToInt()) {
            throw StoreException.broken(path + ": " + (file == null ? Json.parseError(text) : "not a credentials file"));
        }
        ObjectNode out = (ObjectNode) file;
        if (!out.path("profiles").isObject()) {
            out.putObject("profiles");
        }
        return out;
    }

    private void write(ObjectNode file) throws StoreException {
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                restrict(dir, "rwx------");
            }
            Files.writeString(path, pretty(file) + "\n", StandardCharsets.UTF_8);
            restrict(path, "rw-------");
        } catch (IOException e) {
            throw StoreException.broken(path + ": " + e.getMessage());
        }
    }

    /** Two-space indents and {@code "key": value}, as the other kits write the file. */
    private static String pretty(ObjectNode file) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        return Json.mapper().writer(printer).writeValueAsString(file);
    }

    /** POSIX modes where the filesystem has them; Windows relies on the profile directory. */
    private static void restrict(Path target, String mode) throws IOException {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem: nothing to set.
        }
    }

    public boolean has(String profile) {
        try {
            return read().path("profiles").has(profile);
        } catch (StoreException e) {
            return false;
        }
    }

    @Override
    public ProfileRecord load(String profile) throws StoreException {
        JsonNode record = read().path("profiles").get(profile);
        return record == null ? null : ProfileRecord.read(record);
    }

    @Override
    public void save(String profile, ProfileRecord record) throws StoreException {
        ObjectNode file = read();
        file.put("schema", 1);
        ((ObjectNode) file.get("profiles")).set(profile, record.toJson());
        write(file);
    }

    /** Removes the profile; the file goes when it held only that one. */
    @Override
    public void delete(String profile) throws StoreException {
        ObjectNode file = read();
        ObjectNode profiles = (ObjectNode) file.get("profiles");
        if (profiles.remove(profile) == null) {
            return;
        }
        if (profiles.isEmpty()) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw StoreException.broken(e.getMessage());
            }
            return;
        }
        write(file);
    }
}
