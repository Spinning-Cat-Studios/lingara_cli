// Contract B1's streams: data to stdout, everything else to stderr, and
// under `--json` exactly one JSON value on stdout (16.10.26ad D6).
//
// stdout is buffered and written once, by `main`, so an in-process verb that
// canvas apply runs (D7 step 4) hands its JSON back instead.
package com.getlingara.cli.kotlin.output

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Json
import java.io.PrintStream

class Output(private val err: PrintStream, val json: Boolean) {
    val stdout = StringBuilder()

    /** Text for stdout, as is. */
    fun print(text: String) {
        stdout.append(text)
    }

    /** One line on stderr. */
    fun note(line: String) {
        err.print(line + "\n")
        err.flush()
    }

    /** A prompt on stderr, left on the line it ends. */
    fun prompt(text: String) {
        err.print(text)
        err.flush()
    }

    /** One server value: as JSON, or as B1's `FIELD  VALUE` table. */
    fun value(value: JsonNode) {
        if (json) jsonValue(value) else print(Table.render(listOf("FIELD", "VALUE"), Table.fields(value)))
    }

    /** A list: one JSON array, or the schema's columns read from each item by [cells]. */
    fun list(items: List<JsonNode>, columns: List<String>, cells: (JsonNode) -> List<String>) {
        if (json) jsonValue(Json.of(items)) else print(Table.render(columns, items.map(cells)))
    }

    /** A command with nothing to print: nothing, or `{}` under `--json`. */
    fun nothing() {
        if (json) print("{}\n")
    }

    fun jsonValue(value: JsonNode) {
        print(Json.write(value) + "\n")
    }
}
