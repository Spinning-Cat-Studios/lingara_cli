use serde_json::{json, Value};

use crate::check::check;
use crate::model::{parse, Messages, Spec};

const ROOT: &str = env!("CARGO_MANIFEST_DIR");

type Edit = fn(&mut Value, &mut Value);

fn read(rel: &str) -> String {
    std::fs::read_to_string(format!("{ROOT}/{rel}")).unwrap()
}

fn fixture() -> (Value, Value) {
    let spec = serde_json::from_str(&read("tests/fixtures/valid.schema.json")).unwrap();
    let messages = serde_json::from_str(&read("tests/fixtures/valid.messages.json")).unwrap();
    (spec, messages)
}

/// The refusals for the valid fixture after `edit`; a parse refusal counts.
fn refusals(edit: Edit) -> Vec<String> {
    let (mut spec, mut messages) = fixture();
    edit(&mut spec, &mut messages);
    let spec: Spec = match parse(&spec.to_string()) {
        Ok(s) => s,
        Err(e) => return vec![e],
    };
    let messages: Messages = parse(&messages.to_string()).unwrap();
    check(&spec, &messages)
}

fn at<'a>(v: &'a mut Value, pointer: &str) -> &'a mut Value {
    v.pointer_mut(pointer).unwrap_or_else(|| panic!("no {pointer}"))
}

/// Sets `key` on the object at `pointer`, present or not.
fn set(v: &mut Value, pointer: &str, key: &str, value: Value) {
    at(v, pointer).as_object_mut().unwrap().insert(key.into(), value);
}

fn push(v: &mut Value, pointer: &str, item: Value) {
    at(v, pointer).as_array_mut().unwrap().push(item);
}

fn assert_refused(what: &str, edit: Edit, expect: &[&str]) {
    let found = refusals(edit);
    let hit = found.iter().any(|r| expect.iter().all(|e| r.contains(e)));
    assert!(hit, "{what}: expected a refusal containing {expect:?}, got {found:#?}");
}

/// 16.10.26ab AC2
#[test]
fn ac2_committed_schema_is_valid() {
    let spec: Spec = parse(&read("../../spec/cli.schema.json")).unwrap();
    let messages: Messages = parse(&read("../../spec/messages.en.json")).unwrap();
    assert_eq!(check(&spec, &messages), Vec::<String>::new());
    assert_eq!(refusals(|_, _| {}), Vec::<String>::new());
}

/// 16.10.26ab AC3
#[test]
fn ac3_dangerous_and_confirm_travel_together() {
    assert_refused(
        "dangerous without confirm",
        |s, _| *at(s, "/topics/0/commands/2/flag_groups") = json!([]),
        &["topics[0].commands[2]", "must carry the `confirm`"],
    );
    assert_refused(
        "confirm without dangerous",
        |s, _| *at(s, "/topics/0/commands/2/dangerous") = json!(false),
        &["topics[0].commands[2]", "only a dangerous command"],
    );
    assert_refused(
        "env on yes",
        |s, _| set(s, "/flag_groups/confirm/0", "env", json!("LINGARA_YES")),
        &["flag_groups.confirm[0].env"],
    );
    assert_refused(
        "default on confirm",
        |s, _| set(s, "/flag_groups/confirm/1", "default", json!("a@b.c")),
        &["flag_groups.confirm[1].default"],
    );
}

const STRUCTURE: [(&str, Edit, &[&str]); 12] = [
    ("duplicate sibling name", |s, _| *at(s, "/topics/0/commands/1/name") = json!("list"), &["topics[0].commands[1]", "already"]),
    ("duplicate sibling alias", |s, _| set(s, "/topics/1", "aliases", json!(["client"])), &["topics[1]", "already"]),
    ("non-snake name", |s, _| *at(s, "/topics/0/commands/0/name") = json!("List"), &["topics[0].commands[0].name", "snake_case"]),
    ("values on a non-enum", |s, _| set(s, "/topics/0/commands/1/flags/0", "values", json!(["a"])), &["flags[0].values"]),
    ("unknown exit name", |s, _| push(s, "/topics/0/commands/2/exit_codes", json!("nope")), &["commands[2].exit_codes[2]", "not an exit_codes row"]),
    ("implicit exit listed", |s, _| push(s, "/topics/0/commands/2/exit_codes", json!("usage")), &["commands[2].exit_codes[2]", "implicit"]),
    ("unknown flag group", |s, _| set(s, "/topics/0/commands/0", "flag_groups", json!(["nope"])), &["topics[0].commands[0].flag_groups[0]"]),
    ("unresolved conflict", |s, _| *at(s, "/topics/0/commands/1/conflicts") = json!([["ci", "nope"]]), &["topics[0].commands[1].conflicts[0]", "`nope`"]),
    ("duplicate long flag", |s, _| push(s, "/topics/0/commands/1/flags", json!({"name": "json", "type": "bool"})), &["topics[0].commands[1]", "`--json` is declared twice"]),
    ("duplicate short flag", |s, _| set(s, "/topics/0/commands/2", "flags", json!([{"name": "yank", "type": "bool", "short": "y"}])), &["topics[0].commands[2]", "`-y` is declared twice"]),
    ("non-kebab long flag", |s, _| *at(s, "/topics/0/commands/1/flags/0/name") = json!("full_name"), &["flags[0].name", "kebab-case"]),
    ("unknown key", |s, _| *at(s, "/topics/0/commands/0") = json!({"name": "list", "bogus": 1}), &["topics[0].commands[0]", "bogus"]),
];

const RESERVED_AND_TEXT: [(&str, Edit, &[&str]); 11] = [
    ("reserved command", |s, _| push(s, "/commands", json!({"name": "help"})), &["commands[1].name", "reserved"]),
    ("reserved short", |s, _| set(s, "/commands/0/flags/0", "short", json!("h")), &["commands[0].flags[0].short", "framework"]),
    ("reserved global", |s, _| push(s, "/global_flags", json!({"name": "version", "type": "bool"})), &["global_flags[2].name"]),
    ("reserved topic", |s, _| *at(s, "/topics/1/name") = json!("auth"), &["topics[1].name", "reserved"]),
    ("example names another command", |s, _| *at(s, "/topics/0/commands/2/examples/0/argv") = json!("lingara webhook delete x"), &["examples[0].argv", "does not begin"]),
    ("example carries a secret", |s, _| *at(s, "/topics/0/commands/2/examples/0/argv") = json!("lingara client delete lgr_cs_abcdefgh12"), &["examples[0].argv", "secret"]),
    ("private repository in a summary", |s, _| *at(s, "/topics/0/summary") = json!("See the_private repository."), &["topics[0].summary", "_private"]),
    ("home path in a value", |s, _| *at(s, "/topics/0/commands/1/flags/1/values/0") = json!("/home/x"), &["values", "/home/"]),
    ("unknown message id", |s, _| push(s, "/topics/0/commands/2/messages", json!("nope")), &["commands[2].messages[1]", "`nope`"]),
    ("required message missing", |_, m| { at(m, "/messages").as_array_mut().unwrap().remove(0); }, &["required id `confirm_yes_no`"]),
    ("unused message", |_, m| push(m, "/messages", json!({"id": "orphan", "text": "x"})), &["`orphan` is named by no command"]),
];

/// 16.10.26ab AC4
#[test]
fn ac4_each_closed_shape_rule_refuses() {
    for (what, edit, expect) in STRUCTURE.iter().chain(RESERVED_AND_TEXT.iter()) {
        assert_refused(what, *edit, expect);
    }
}

/// 16.10.26ab AC5
#[test]
fn ac5_base_exit_codes_present_and_unique() {
    let spec: Spec = parse(&read("../../spec/cli.schema.json")).unwrap();
    let base = crate::check::exits::BASE_EXITS;
    let rows: Vec<(i32, &str)> = spec.exit_codes.iter().map(|r| (r.code, r.name.as_str())).filter(|(c, _)| base.iter().any(|(b, _)| b == c)).collect();
    assert_eq!(rows, base.to_vec(), "every base row, in order, never renamed");
    // Rows a later ADR appends sit in 9..=129 (16.10.26ae D10 appends 9–18).
    assert!(spec.exit_codes.iter().filter(|r| !base.iter().any(|(b, _)| *b == r.code)).all(|r| (9..=129).contains(&r.code)));
    let disabled = spec.exit_codes.iter().find(|r| r.name == "platform_disabled").unwrap();
    assert_eq!(disabled.message.as_deref(), Some("platform_disabled"));
    assert_refused("duplicate code", |s, _| *at(s, "/exit_codes/9/code") = json!(8), &["exit_codes[9].code", "twice"]);
    assert_refused("duplicate name", |s, _| *at(s, "/exit_codes/7/name") = json!("refused"), &["exit_codes[7].name", "twice"]);
    assert_refused("base row missing", |s, _| { at(s, "/exit_codes").as_array_mut().unwrap().remove(4); }, &["base row 4"]);
    assert_refused("disabled message", |s, _| *at(s, "/exit_codes/8/message") = json!("confirm_declined"), &["platform_disabled` row's message"]);
    assert_refused("appended out of range", |s, _| push(s, "/exit_codes", json!({"code": 200, "name": "late", "summary": "x"})), &["exit_codes[10].code", "9..=129"]);
}
