use serde_json::json;

use super::*;

fn row(cells: &[&str]) -> Vec<String> {
    cells.iter().map(|c| c.to_string()).collect()
}

/// 16.10.26ad AC9 the table is B1's
#[test]
fn the_table_is_b1s() {
    let text = render(&["ID", "NAME", "SERVING"], &[row(&["7d1e", "Flashcards", "1.0.0"]), row(&["7d1e2f30-long", "Tone drills", ""])]);
    assert_eq!(text, "ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n");
    // No trailing whitespace on any line, and nothing truncated.
    assert!(text.lines().all(|l| !l.ends_with(' ')));
    let long = "x".repeat(200);
    assert!(render(&["A"], &[row(&[&long])]).contains(&long));
    // A header wider than its cells still pads them.
    assert_eq!(render(&["SCOPES", "B"], &[row(&["a", "b"])]), "SCOPES  B\na       b\n");
}

#[test]
fn cells_render_json_values() {
    assert_eq!(cell(&json!(null)), "-");
    assert_eq!(cell(&json!([])), "-");
    assert_eq!(cell(&json!(["a:read", "b:write"])), "a:read,b:write");
    assert_eq!(cell(&json!(true)), "true");
    assert_eq!(cell(&json!(3)), "3");
    assert_eq!(cell(&json!({"a": 1})), r#"{"a":1}"#);
    assert_eq!(fields(&json!({"id": "x", "name": null})), vec![row(&["id", "x"]), row(&["name", "-"])]);
}
