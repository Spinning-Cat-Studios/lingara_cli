package com.getlingara.cli.java.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Contract B1's streams: data to stdout, everything else to stderr, and under {@code --json}
 * exactly one JSON value on stdout (16.10.26ad D6).
 *
 * <p>stdout is buffered and written once, by {@code Main}, so an in-process verb that canvas
 * apply runs (D7 step 4) hands its JSON back instead.
 */
public final class Output {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream err;
    private final boolean json;

    public Output(PrintStream err, boolean json) {
        this.err = err;
        this.json = json;
    }

    public boolean json() {
        return json;
    }

    /** stderr itself, for a child's relayed lines, which share it with this run's notes. */
    public PrintStream err() {
        return err;
    }

    public byte[] stdout() {
        return stdout.toByteArray();
    }

    /** Drops what was printed so far: {@code apps dev} prints one value, at its exit. */
    public void clearStdout() {
        stdout.reset();
    }

    /** Text for stdout, as is. */
    public void print(String text) {
        stdout.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** One line on stderr. */
    public void note(String line) {
        err.print(line + "\n");
        err.flush();
    }

    /** A prompt on stderr, left on the line it ends. */
    public void prompt(String text) {
        err.print(text);
        err.flush();
    }

    /** One server value: as JSON, or as B1's {@code FIELD  VALUE} table. */
    public void value(JsonNode value) {
        if (json) {
            jsonValue(value);
        } else {
            print(Table.render(List.of("FIELD", "VALUE"), Table.fields(value)));
        }
    }

    /** A list: one JSON array, or the schema's columns read from each item by {@code cells}. */
    public void list(List<JsonNode> items, List<String> columns, Function<JsonNode, List<String>> cells) {
        if (json) {
            var array = Json.array();
            items.forEach(array::add);
            jsonValue(array);
        } else {
            List<List<String>> rows = new ArrayList<>();
            items.forEach(item -> rows.add(cells.apply(item)));
            print(Table.render(columns, rows));
        }
    }

    /** A command with nothing to print: nothing, or {@code {}} under {@code --json}. */
    public void nothing() {
        if (json) {
            print("{}\n");
        }
    }

    public void jsonValue(JsonNode value) {
        print(Json.write(value) + "\n");
    }
}
