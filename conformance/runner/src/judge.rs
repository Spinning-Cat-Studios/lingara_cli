//! `expect` (ADR 16.10.26ac D3): the exit, each stream's matcher, B1's
//! human table and the stored credentials.

use std::path::Path;

use serde_json::Value;

use crate::case::schema::Schema;
use crate::case::{Case, Presence, StreamMatch, Table, TextMatch};
use crate::mock::platform_dev::{DevCall, Minted};
use crate::mock::{fill, fill_json};
use crate::spawn::Outcome;

#[path = "judge_apps.rs"]
pub mod apps;

/// What an `apps` case's three judges read (ADR 16.10.26ae D11).
pub struct AppsEvidence<'a> {
    pub dev_calls: &'a [DevCall],
    pub minted: &'a [Minted],
    pub subs: &'a [(String, String)],
    /// The case's directory: its `HOME`, and the parent of its project.
    pub home: &'a Path,
    /// `conformance/golden/`.
    pub goldens: &'a Path,
}

/// `expect.calls`, `expect.child_env` and `expect.files`; the minted
/// secrets are checked absent from every file whether or not a case asks.
pub fn judge_apps(case: &Case, e: &AppsEvidence) -> Vec<String> {
    let mut out = Vec::new();
    if let Some(want) = &case.expect.calls {
        out.extend(apps::calls(want, e.dev_calls, e.subs));
    }
    if let Some(want) = &case.expect.child_env {
        out.extend(apps::child_env(want, &e.home.join("child-env.json"), e.minted));
    }
    if case.group() == "apps" {
        out.extend(apps::files(case.expect.files.as_ref(), e.home, e.goldens, e.minted));
    }
    out
}

/// Where each fragment of `fragments` is found in `hay`, in order, starting
/// at byte `from`: the end of the last one, or `None`.
pub fn find_in_order(hay: &str, fragments: &[String], from: usize) -> Option<usize> {
    let mut at = from.min(hay.len());
    for f in fragments {
        at += hay.get(at..)?.find(f.as_str())? + f.len();
    }
    Some(at)
}

/// A message template with `vars` filled; each placeholder left unfilled
/// splits it, so `Type your developer email ({hint})` matches any hint.
pub fn fragments_of(template: &str, vars: &std::collections::BTreeMap<String, String>) -> Vec<String> {
    let filled = vars.iter().fold(template.to_string(), |t, (k, v)| t.replace(&format!("{{{k}}}"), v));
    let mut out = Vec::new();
    let mut rest = filled.as_str();
    while let Some(open) = rest.find('{') {
        let close = rest[open..].find('}').map_or(rest.len(), |c| open + c + 1);
        out.push(rest[..open].to_string());
        rest = &rest[close..];
    }
    out.push(rest.to_string());
    out.into_iter().filter(|f| !f.is_empty()).collect()
}

/// The fragments one `interact.expect` waits for.
pub fn text_fragments(m: &TextMatch, schema: &Schema) -> Vec<String> {
    match (&m.message, &m.contains) {
        (Some(id), _) => fragments_of(schema.messages.get(id).map_or("", String::as_str), &m.vars),
        (_, Some(text)) => vec![text.clone()],
        _ => Vec::new(),
    }
}

/// B1's table: lines split on runs of two or more spaces, which must also
/// re-render byte for byte (left-aligned, padded to the widest cell, two
/// spaces apart, no trailing whitespace, `-` for an empty value).
pub fn parse_table(text: &str) -> Result<Vec<Vec<String>>, String> {
    let rows: Vec<Vec<String>> = text
        .lines()
        .map(|l| l.split("  ").map(str::trim).filter(|c| !c.is_empty()).map(str::to_string).collect())
        .collect();
    let width = rows.first().map_or(0, Vec::len);
    if rows.iter().any(|r| r.len() != width) {
        return Err("a row's cell count differs from the header's: a blank cell, or a missing column".into());
    }
    if text.chars().any(|c| matches!(c, '|' | '+' | '─' | '│' | '┼' | '═')) {
        return Err("the table carries a border character".into());
    }
    if render_table(&rows) != text {
        return Err(format!("the table is not B1's layout; expected:\n{}", render_table(&rows)));
    }
    Ok(rows)
}

pub fn render_table(rows: &[Vec<String>]) -> String {
    let columns = rows.first().map_or(0, Vec::len);
    let widths: Vec<usize> =
        (0..columns).map(|i| rows.iter().map(|r| r[i].chars().count()).max().unwrap_or(0)).collect();
    let line = |r: &Vec<String>| {
        let cells: Vec<String> = r.iter().zip(&widths).map(|(c, w)| format!("{c:<w$}")).collect();
        cells.join("  ").trim_end().to_string()
    };
    rows.iter().map(|r| format!("{}\n", line(r))).collect()
}

/// Every way the run missed `expect`.
pub fn judge(case: &Case, outcome: &Outcome, schema: &Schema, subs: &[(String, String)], home: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let want = schema.exits.get(&case.expect.exit).copied();
    if outcome.exit != want {
        let got = outcome.exit.map_or("no exit code".to_string(), |c| format!("{c} ({})", schema.exit_name(c).unwrap_or("?")));
        out.push(format!("exit: expected {} ({}), got {got}", case.expect.exit, want.unwrap_or(-1)));
    }
    let stdout = String::from_utf8_lossy(&outcome.stdout);
    if let Some(m) = &case.expect.stdout {
        out.extend(judge_stream("stdout", m, &stdout, schema, subs));
    }
    if let Some(m) = &case.expect.stderr {
        out.extend(judge_stream("stderr", m, &outcome.stderr, schema, subs));
    }
    if let Some(want) = case.expect.stored_credentials {
        let present = home.join("credentials.json").exists();
        if present != (want == Presence::Present) {
            out.push(format!("stored_credentials: expected {want:?}, credentials.json present = {present}"));
        }
    }
    out
}

fn judge_stream(stream: &str, m: &StreamMatch, text: &str, schema: &Schema, subs: &[(String, String)]) -> Vec<String> {
    let miss = |what: String| vec![format!("{stream}: {what}; got {text:?}")];
    if m.empty == Some(true) && !text.is_empty() {
        return miss("expected empty".into());
    }
    if m.json.as_ref().is_some_and(|want| !json_equal(want, text, subs)) {
        return miss(format!("expected JSON {}", m.json.as_ref().map_or(Value::Null, Clone::clone)));
    }
    if let Some(table) = &m.table {
        return judge_table(stream, table, text);
    }
    for c in m.contains.iter().flatten().map(|c| fill(c, subs)) {
        if !text.contains(&c) {
            return miss(format!("expected it to contain {c:?}"));
        }
    }
    if let Some(id) = &m.message {
        let fragments = fragments_of(schema.messages.get(id).map_or("", String::as_str), &m.vars);
        if find_in_order(text, &fragments, 0).is_none() {
            return miss(format!("expected message `{id}`"));
        }
    }
    match &m.json_error {
        Some(want) => judge_json_error(stream, want, text, schema),
        None => Vec::new(),
    }
}

/// `text` is one JSON value equal to `want`, both without `null` members.
fn json_equal(want: &Value, text: &str, subs: &[(String, String)]) -> bool {
    let got = serde_json::from_str::<Value>(text.trim()).map(|v| drop_nulls(&v));
    got.ok() == Some(drop_nulls(&fill_json(want, subs)))
}

fn judge_table(stream: &str, want: &Table, text: &str) -> Vec<String> {
    let rows = match parse_table(text) {
        Ok(rows) => rows,
        Err(e) => return vec![format!("{stream}: {e}; got {text:?}")],
    };
    let (columns, body) = rows.split_first().map_or((&[][..], &[][..]), |(h, b)| (h.as_slice(), b));
    if columns != want.columns.as_slice() || body != want.rows.as_slice() {
        return vec![format!("{stream}: table {rows:?} is not columns {:?} rows {:?}", want.columns, want.rows)];
    }
    Vec::new()
}

/// One JSON line, `{"error":{…}}`, holding each listed field; `exit` may be
/// written as a D3 name.
fn judge_json_error(stream: &str, want: &std::collections::BTreeMap<String, Value>, text: &str, schema: &Schema) -> Vec<String> {
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let error = match lines.as_slice() {
        [one] => serde_json::from_str::<Value>(one).ok().and_then(|v| v.get("error").cloned()),
        _ => None,
    };
    let Some(error) = error else { return vec![format!("{stream}: expected one {{\"error\":…}} JSON line; got {text:?}")] };
    let mut out = Vec::new();
    for (key, value) in want {
        let value = match (key.as_str(), value) {
            ("exit", Value::String(name)) => schema.exits.get(name).map_or(value.clone(), |c| Value::from(*c)),
            _ => value.clone(),
        };
        if error.get(key) != Some(&value) {
            out.push(format!("{stream}: error.{key} is {:?}, expected {value}", error.get(key)));
        }
    }
    out
}

/// JSON with every `null`-valued key dropped, as the API clients compare.
pub fn drop_nulls(value: &Value) -> Value {
    match value {
        Value::Object(o) => Value::Object(o.iter().filter(|(_, v)| !v.is_null()).map(|(k, v)| (k.clone(), drop_nulls(v))).collect()),
        Value::Array(a) => Value::Array(a.iter().map(drop_nulls).collect()),
        other => other.clone(),
    }
}
