//! Contract B1's streams: data to stdout, everything else to stderr, and
//! under `--json` exactly one JSON value on stdout (D6).
//!
//! stdout is buffered and written once, by `main`, so an in-process verb
//! that canvas apply runs (D7 step 4) hands its JSON back instead.

pub mod errors;
pub mod table;

use std::io::Write;

use serde_json::Value;

pub struct Output {
    pub stdout: Vec<u8>,
    err: Box<dyn Write>,
    pub json: bool,
}

impl Output {
    pub fn new(err: Box<dyn Write>, json: bool) -> Output {
        Output { stdout: Vec::new(), err, json }
    }

    /// Text for stdout, as is.
    pub fn print(&mut self, text: &str) {
        self.stdout.extend_from_slice(text.as_bytes());
    }

    /// One line on stderr.
    pub fn note(&mut self, line: &str) {
        let _ = writeln!(self.err, "{line}");
        let _ = self.err.flush();
    }

    /// A prompt on stderr, left on the line it ends.
    pub fn prompt(&mut self, text: &str) {
        let _ = write!(self.err, "{text}");
        let _ = self.err.flush();
    }

    /// One server value: as JSON, or as B1's `FIELD  VALUE` table.
    pub fn value(&mut self, value: &Value) {
        if self.json {
            self.json_value(value);
        } else {
            self.print(&table::render(&["FIELD", "VALUE"], &table::fields(value)));
        }
    }

    /// A list: one JSON array, or the schema's columns read from each item
    /// by `cells`.
    pub fn list(&mut self, items: &[Value], columns: &[&str], cells: &dyn Fn(&Value) -> Vec<String>) {
        if self.json {
            self.json_value(&Value::Array(items.to_vec()));
        } else {
            let rows: Vec<Vec<String>> = items.iter().map(cells).collect();
            self.print(&table::render(columns, &rows));
        }
    }

    /// A command with nothing to print: nothing, or `{}` under `--json`.
    pub fn nothing(&mut self) {
        if self.json {
            self.print("{}\n");
        }
    }

    pub fn json_value(&mut self, value: &Value) {
        let text = serde_json::to_string(value).unwrap_or_else(|_| "null".into());
        self.print(&format!("{text}\n"));
    }
}
