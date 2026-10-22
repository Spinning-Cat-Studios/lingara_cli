//! Writing `platform.toml`, its example and `.gitignore`, byte for byte as
//! `spec/templates.md` says, so every kit leaves the same files behind. The
//! edits are line-based on purpose: a developer's comments and layout
//! outside the touched row survive.

use std::path::Path;

use super::{EXAMPLE, FILE, PLACEHOLDER_ID};
use crate::exit::Fail;

pub const FILE_HEADER: &str = "# platform.toml — gitignored; copy from platform.toml.example.\n\
# Never put a secret here. lingara refuses to read a value that looks like one.\n\
schema = 1\n";

pub const EXAMPLE_HEADER: &str = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n\
# then run lingara apps update to register your own private copy of each app.\n\
schema = 1\n";

/// A row as `create` and `pin` write it.
pub struct NewRow<'a> {
    pub name: &'a str,
    pub path: &'a str,
    pub port: u16,
    pub start: Option<&'a str>,
    /// `("manifest" | "manifest_command", value)`.
    pub manifest: Option<(&'a str, &'a str)>,
}

/// A TOML basic string.
pub fn quote(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

fn row_text(row: &NewRow, id: Option<&str>) -> String {
    let mut out = format!("\n[[apps]]\nname = {}\n", quote(row.name));
    if let Some(id) = id {
        out.push_str(&format!("id = {}\n", quote(id)));
    }
    out.push_str(&format!("path = {}\nport = {}\n", quote(row.path), row.port));
    if let Some(start) = row.start {
        out.push_str(&format!("start = {}\n", quote(start)));
    }
    if let Some((key, value)) = row.manifest {
        out.push_str(&format!("{key} = {}\n", quote(value)));
    }
    out
}

fn io(path: &Path, e: std::io::Error) -> Fail {
    Fail::failure(format!("{}: {e}", path.display()))
}

fn read_or(path: &Path, header: &str) -> Result<String, Fail> {
    match std::fs::read_to_string(path) {
        Ok(mut text) => {
            if !text.is_empty() && !text.ends_with('\n') {
                text.push('\n');
            }
            Ok(text)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(header.to_string()),
        Err(e) => Err(io(path, e)),
    }
}

/// Whether `text` holds a row named `name`.
pub fn has_row(text: &str, name: &str) -> bool {
    let line = format!("name = {}", quote(name));
    rows(text).iter().any(|(start, end)| text.lines().skip(*start).take(end - start).any(|l| l.trim() == line))
}

/// Appends the row to `file` (with `id`, if any) and, when the example
/// beside it has no row of that name, to the example (with the placeholder).
pub fn pin_row(file: &Path, row: &NewRow, id: Option<&str>) -> Result<(), Fail> {
    let text = read_or(file, FILE_HEADER)? + &row_text(row, id);
    std::fs::write(file, text).map_err(|e| io(file, e))?;
    let example = file.with_file_name(EXAMPLE);
    let current = read_or(&example, EXAMPLE_HEADER)?;
    if !has_row(&current, row.name) {
        let text = current + &row_text(row, Some(PLACEHOLDER_ID));
        std::fs::write(&example, text).map_err(|e| io(&example, e))?;
    }
    Ok(())
}

/// Each `[[apps]]` row's line span `[start, end)`: up to the next table
/// header that is not its own `[apps.env]`.
fn rows(text: &str) -> Vec<(usize, usize)> {
    let lines: Vec<&str> = text.lines().collect();
    let header = |l: &str| l.trim_start().starts_with('[') && l.trim() != "[apps.env]";
    let starts: Vec<usize> = lines.iter().enumerate().filter(|(_, l)| l.trim() == "[[apps]]").map(|(i, _)| i).collect();
    starts.iter().map(|&s| (s, (s + 1..lines.len()).find(|&i| header(lines[i])).unwrap_or(lines.len()))).collect()
}

fn span_of(text: &str, name: &str) -> Option<(usize, usize)> {
    let line = format!("name = {}", quote(name));
    rows(text).into_iter().find(|(s, e)| text.lines().skip(*s).take(e - s).any(|l| l.trim() == line))
}

fn joined(lines: &[&str]) -> String {
    lines.iter().map(|l| format!("{l}\n")).collect()
}

/// Writes `id` into the row named `name`: replaces its `id` line, or adds one
/// directly after its `name` line.
pub fn set_id(file: &Path, name: &str, id: &str) -> Result<(), Fail> {
    let text = std::fs::read_to_string(file).map_err(|e| io(file, e))?;
    let Some((start, end)) = span_of(&text, name) else { return Ok(()) };
    let mut lines: Vec<String> = text.lines().map(str::to_string).collect();
    let id_line = format!("id = {}", quote(id));
    let in_row = |l: &str, key: &str| l.trim_start().starts_with(key);
    match (start..end).find(|&i| in_row(&lines[i], "id ") || in_row(&lines[i], "id=")) {
        Some(i) => lines[i] = id_line,
        None => {
            let after = (start..end).find(|&i| in_row(&lines[i], "name")).unwrap_or(start);
            lines.insert(after + 1, id_line);
        }
    }
    let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
    std::fs::write(file, joined(&refs)).map_err(|e| io(file, e))
}

/// Removes the row named `name`; `false` when there is none.
pub fn remove_row(file: &Path, name: &str) -> Result<bool, Fail> {
    let text = std::fs::read_to_string(file).map_err(|e| io(file, e))?;
    let Some((mut start, end)) = span_of(&text, name) else { return Ok(false) };
    let lines: Vec<&str> = text.lines().collect();
    if end == lines.len() {
        while start > 0 && lines[start - 1].trim().is_empty() {
            start -= 1;
        }
    }
    let kept: Vec<&str> = lines[..start].iter().chain(&lines[end..]).copied().collect();
    std::fs::write(file, joined(&kept)).map_err(|e| io(file, e))?;
    Ok(true)
}

/// Adds a `platform.toml` line to `<dir>/.gitignore` when no line is one.
pub fn ensure_gitignore(dir: &Path) -> Result<(), Fail> {
    let path = dir.join(".gitignore");
    let mut text = read_or(&path, "")?;
    if text.lines().any(|l| l.trim() == FILE) {
        return Ok(());
    }
    text.push_str(FILE);
    text.push('\n');
    std::fs::write(&path, text).map_err(|e| io(&path, e))
}
