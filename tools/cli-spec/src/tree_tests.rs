use serde_json::{json, Value};

use crate::model::{parse, Spec};
use crate::tree::{diff, project_kit, project_spec};

const ROOT: &str = env!("CARGO_MANIFEST_DIR");

fn spec() -> Spec {
    parse(&std::fs::read_to_string(format!("{ROOT}/tests/fixtures/valid.schema.json")).unwrap()).unwrap()
}

fn kit() -> Value {
    serde_json::from_str(&std::fs::read_to_string(format!("{ROOT}/tests/fixtures/kit-tree.json")).unwrap()).unwrap()
}

fn differences(edit: fn(&mut Value)) -> Vec<String> {
    let mut tree = kit();
    edit(&mut tree);
    diff(&project_spec(&spec()), &project_kit(&tree.to_string()).unwrap())
}

fn at<'a>(v: &'a mut Value, pointer: &str) -> &'a mut Value {
    v.pointer_mut(pointer).unwrap_or_else(|| panic!("no {pointer}"))
}

fn assert_named(what: &str, edit: fn(&mut Value), expect: &str) {
    let found = differences(edit);
    assert!(found.iter().any(|d| d == expect), "{what}: expected {expect:?}, got {found:#?}");
}

/// 16.10.26ab AC7
#[test]
fn ac7_tree_diff_fails_in_both_directions() {
    // Identical, framework built-ins included (help, completion, version,
    // --help/-h, the root --version and __tree), passes.
    assert_eq!(differences(|_| {}), Vec::<String>::new());

    assert_named(
        "missing command",
        |t| {
            at(t, "/topics/0/commands").as_array_mut().unwrap().remove(0);
        },
        "oauth_client list: in the schema, missing from the kit",
    );
    assert_named(
        "extra command",
        |t| at(t, "/topics/0/commands").as_array_mut().unwrap().push(json!({"name": "rotate"})),
        "oauth_client rotate: in the kit, missing from the schema",
    );
    assert_named(
        "flag type change",
        |t| *at(t, "/topics/0/commands/2/flags/1/type") = json!("bool"),
        "oauth_client delete --confirm: type string ≠ bool",
    );
    assert_named(
        "missing alias",
        |t| *at(t, "/topics/0/aliases") = json!([]),
        "oauth_client: aliases client ≠ (none)",
    );
}
