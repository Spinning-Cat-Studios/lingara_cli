//! B1's human table and the stream matchers (ADR 16.10.26ac D3).

use crate::case;
use crate::case_tests::real_schema;
use crate::judge::{fragments_of, judge, parse_table};
use crate::spawn::Outcome;

const TABLE: &str = "ID   NAME        CLIENT\nob1  Flashcards  -\nob2  Tone        lgr_cid_x\n";

/// 16.10.26ac AC2
#[test]
fn human_table_is_judged_byte_exactly() {
    let rows = parse_table(TABLE).expect("B1's layout parses");
    assert_eq!(rows[0], ["ID", "NAME", "CLIENT"]);
    assert_eq!(rows[1], ["ob1", "Flashcards", "-"], "`-` is an empty value's cell");

    let missing_column = "ID   NAME\nob1  Flashcards  -\n";
    let blank_cell = "ID   NAME        CLIENT\nob1              -\n";
    let border = "ID  | NAME\nob1 | Flashcards\n";
    let trailing = "ID   NAME        CLIENT\nob1  Flashcards  - \n";
    let misaligned = "ID  NAME  CLIENT\nob1  Flashcards  -\n";
    for bad in [missing_column, blank_cell, border, trailing, misaligned] {
        assert!(parse_table(bad).is_err(), "{bad:?} must fail");
    }

    let case = |columns: &str| {
        case::parse(&format!(
            "id: b1.t\ntitle: t\nbehaviours: [B1]\ncommand: apps list\nargv: [apps, list]\n\
             expect: {{ exit: ok, stdout: {{ table: {{ columns: {columns}, rows: [[ob1, Flashcards, \"-\"], [ob2, Tone, lgr_cid_x]] }} }} }}\n"
        ))
        .unwrap()
    };
    let outcome = Outcome { exit: Some(0), stdout: TABLE.as_bytes().to_vec(), ..Outcome::default() };
    let home = std::env::temp_dir();
    assert!(judge(&case("[ID, NAME, CLIENT]"), &outcome, &real_schema(), &[], &home).is_empty());
    let reordered = judge(&case("[ID, CLIENT, NAME]"), &outcome, &real_schema(), &[], &home);
    assert!(reordered.iter().any(|m| m.contains("table")), "a reordered column fails: {reordered:?}");
}

/// A message id renders with its vars; an unfilled placeholder matches any
/// text, so a kit's own hint or cascade sentence may follow.
#[test]
fn messages_match_with_placeholders_open() {
    let vars = [("name".to_string(), "My server".to_string())].into_iter().collect();
    let parts = fragments_of("Delete {kind} {name} ({id})?", &vars);
    assert_eq!(parts, ["Delete ", " My server (", ")?"]);
    let found = crate::judge::find_in_order("Delete oauth_client My server (oc_1)? [y/N]", &parts, 0);
    assert!(found.is_some());
    assert!(crate::judge::find_in_order("Delete oauth_client Other (oc_1)?", &parts, 0).is_none());
}
