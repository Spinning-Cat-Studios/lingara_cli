//! Contract B1's human table, rendered here rather than by a table crate,
//! because those draw borders and wrap cells, which B1 forbids (D6).

use serde_json::Value;

/// A header row and the rows under it: left-aligned, padded to the widest
/// cell, two spaces apart, no trailing whitespace, never truncated.
pub fn render(columns: &[&str], rows: &[Vec<String>]) -> String {
    let header: Vec<String> = columns.iter().map(|c| c.to_string()).collect();
    let all: Vec<&Vec<String>> = std::iter::once(&header).chain(rows).collect();
    let widths: Vec<usize> =
        (0..columns.len()).map(|i| all.iter().map(|r| r.get(i).map_or(1, |c| cell_text(c).chars().count())).max().unwrap_or(0)).collect();
    let mut out = String::new();
    for row in all {
        let cells: Vec<String> = (0..columns.len())
            .map(|i| format!("{:<w$}", cell_text(row.get(i).map_or("", String::as_str)), w = widths[i]))
            .collect();
        out.push_str(cells.join("  ").trim_end());
        out.push('\n');
    }
    out
}

/// An empty value is `-`, so no cell is blank and the split stays exact.
fn cell_text(cell: &str) -> &str {
    if cell.is_empty() { "-" } else { cell }
}

/// One JSON value as a cell: a string as itself, a list of scalars joined
/// with commas, `null` and an empty list as `-`, anything else compact JSON.
pub fn cell(value: &Value) -> String {
    match value {
        Value::Null => "-".into(),
        Value::String(s) => s.clone(),
        Value::Array(items) if items.is_empty() => "-".into(),
        Value::Array(items) if items.iter().all(|v| !v.is_object() && !v.is_array()) => {
            items.iter().map(cell).collect::<Vec<_>>().join(",")
        }
        other => other.to_string(),
    }
}

/// A `get`: B1's two-column `FIELD  VALUE` form, one row per member.
pub fn fields(object: &Value) -> Vec<Vec<String>> {
    let Some(members) = object.as_object() else { return vec![vec!["value".into(), cell(object)]] };
    members.iter().map(|(k, v)| vec![k.clone(), cell(v)]).collect()
}

#[cfg(test)]
#[path = "table_tests.rs"]
mod table_tests;
