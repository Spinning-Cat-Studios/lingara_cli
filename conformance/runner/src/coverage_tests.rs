//! D7's coverage rules, against a fixture schema and a copy of the
//! committed cases.

use std::path::Path;

use crate::case::{self, schema};
use crate::case_tests::{real_routes, repo};
use crate::coverage::{Report, check};

/// The commands the committed cases cover, plus `oauth_client update`
/// with one `conflicts` set and no case at all.
const SCHEMA: &str = r#"{ "schema": 1, "binary": "lingara",
  "exit_codes": [
    { "code": 0, "name": "ok" }, { "code": 1, "name": "failure" }, { "code": 2, "name": "usage" },
    { "code": 3, "name": "auth_required" }, { "code": 4, "name": "not_found" }, { "code": 5, "name": "refused" },
    { "code": 6, "name": "not_confirmed" }, { "code": 7, "name": "unavailable" }, { "code": 8, "name": "platform_disabled" },
    { "code": 130, "name": "interrupted" } ],
  "commands": [ { "name": "login", "exit_codes": ["auth_required", "platform_disabled"] } ],
  "topics": [
    { "name": "apps", "commands": [ { "name": "list", "exit_codes": ["auth_required", "refused", "platform_disabled"] } ] },
    { "name": "oauth_client", "aliases": ["client"], "commands": [
      { "name": "get", "exit_codes": ["auth_required", "not_found", "refused", "platform_disabled"] },
      { "name": "update", "conflicts": [["name", "scope"]], "exit_codes": ["auth_required"] },
      { "name": "delete", "dangerous": true, "exit_codes": ["not_found", "not_confirmed", "refused", "auth_required", "platform_disabled"] } ] } ] }"#;

/// The committed cases in a temp dir beside the fixture schema, with
/// `edit` applied first.
fn report(strict: bool, edit: impl Fn(&Path)) -> Report {
    let dir = tempfile::tempdir().unwrap();
    let cases = dir.path().join("cases");
    for group in ["b1", "b2", "b3", "b4"] {
        std::fs::create_dir_all(cases.join(group)).unwrap();
        for f in std::fs::read_dir(repo().join("conformance/cases").join(group)).unwrap().flatten() {
            std::fs::copy(f.path(), cases.join(group).join(f.file_name())).unwrap();
        }
    }
    // B6 and B7 are written (ADR 16.10.26ad), and their committed cases name
    // commands this fixture schema lacks, so one stand-in cites both.
    std::fs::create_dir_all(cases.join("b6")).unwrap();
    let cites = "id: b6.cited\ntitle: t\nbehaviours: [B6, B7]\ncommand: login\nargv: [login]\nexpect: { exit: auth_required }\n";
    std::fs::write(cases.join("b6/cited.yaml"), cites).unwrap();
    std::fs::write(dir.path().join("cli.schema.json"), SCHEMA).unwrap();
    std::fs::copy(repo().join("spec/messages.en.json"), dir.path().join("messages.en.json")).unwrap();
    edit(&cases);
    let schema = schema::load(&dir.path().join("cli.schema.json")).unwrap();
    let contract = std::fs::read_to_string(repo().join("conformance/CONTRACT.md")).unwrap();
    let loaded = case::load_dir(&cases).unwrap();
    check(&loaded, &schema, &real_routes(), &contract, strict)
}

fn has(found: &[String], needle: &str) -> bool {
    found.iter().any(|e| e.contains(needle))
}

fn remove(path: &'static str) -> impl Fn(&Path) {
    move |cases: &Path| std::fs::remove_file(cases.join(path)).unwrap()
}

/// The case `oauth_client update`'s `conflicts` set asks for: two of its
/// flags, `usage`, and no request.
fn add_conflict_case(cases: &Path) {
    let yaml = "id: op.update-conflicts\ntitle: t\nbehaviours: [B2]\ncommand: oauth_client update\n\
                argv: [client, update, oc_1, --name, a, --scope, b]\ncredentials: stored\nexpect: { exit: usage }\n";
    std::fs::create_dir_all(cases.join("op")).unwrap();
    std::fs::write(cases.join("op/update-conflicts.yaml"), yaml).unwrap();
}

/// 16.10.26ac AC5
#[test]
fn every_command_and_every_dangerous_ritual_is_covered() {
    let lax = report(false, |_| {});
    assert!(lax.errors.is_empty(), "{:?}", lax.errors);
    assert!(has(&lax.warnings, "`oauth_client update` has no case"), "rule 2 warns: {:?}", lax.warnings);
    assert!(has(&lax.warnings, r#"conflicts ["name", "scope"] has no usage case"#), "{:?}", lax.warnings);

    let strict = report(true, |_| {});
    assert!(has(&strict.errors, "rule 2: `oauth_client update` has no case"), "{:?}", strict.errors);
    assert!(has(&strict.errors, "has no usage case"), "{:?}", strict.errors);
    let covered = report(true, add_conflict_case);
    assert!(covered.errors.is_empty(), "two of the set's flags, usage and no request satisfy it: {:?}", covered.errors);
    let trio = report(true, remove("b3/non-tty-flags.yaml"));
    assert!(has(&trio.errors, "dangerous `oauth_client delete` has no NonTtyFlags case"), "{:?}", trio.errors);
    let trio_lax = report(false, remove("b3/tty-ok.yaml"));
    assert!(trio_lax.errors.is_empty() && has(&trio_lax.warnings, "no TtyOk case"), "{trio_lax:?}");

    let always = [
        ("b3/tty-declined.yaml", "the suite has no TtyDeclined case"),
        ("b3/tty-mismatch.yaml", "the suite has no TtyMismatch case"),
        ("b3/non-tty-flags-mismatch.yaml", "the suite has no NonTtyFlagsMismatch case"),
        ("b4/login-platform-disabled.yaml", "a login expecting platform_disabled"),
        ("b4/route-platform-disabled.yaml", "a developer route expecting platform_disabled"),
        ("b4/not-found-stays-not-found.yaml", "a coded 404 expecting not_found"),
        ("b4/invalid-grant-stays-auth-required.yaml", "a token refusal expecting auth_required"),
        ("b2/stale-token-refreshes.yaml", "a 401-refresh case expecting ok"),
        ("b2/second-401-is-auth-required.yaml", "a second-401 case expecting auth_required"),
        ("b2/empty-404-is-failure.yaml", "an empty-404 case expecting failure"),
    ];
    for (file, gap) in always {
        let found = report(false, remove(file));
        assert!(has(&found.errors, gap), "removing {file} must fail with {gap:?}: {:?}", found.errors);
    }

    let bad_message = |cases: &Path| {
        let p = cases.join("b3/tty-declined.yaml");
        let text = std::fs::read_to_string(&p).unwrap().replace("confirm_declined", "confirm_nope");
        std::fs::write(p, text).unwrap();
    };
    assert!(has(&report(false, bad_message).errors, "message `confirm_nope` is not in messages.en.json"));
    let bad_exit = |cases: &Path| {
        let p = cases.join("b2/no-credentials.yaml");
        let text = std::fs::read_to_string(&p).unwrap().replace("exit: auth_required", "exit: not_confirmed");
        std::fs::write(p, text).unwrap();
    };
    assert!(has(&report(false, bad_exit).errors, "exit `not_confirmed` is not one `apps list` can return"));
}

/// Rule 6 reads `CONTRACT.md`: every written behaviour needs a case citing
/// it, B6 and B7 too since ADR 16.10.26ad wrote them out.
#[test]
fn every_written_behaviour_is_cited() {
    let contract = std::fs::read_to_string(repo().join("conformance/CONTRACT.md")).unwrap();
    let written = crate::coverage::written(&contract);
    assert_eq!(format!("{written:?}"), "[B1, B2, B3, B4, B5, B6, B7]");
    let reserved = contract.replace("## B7 — Canvas apply", "## B7 — Canvas apply (reserved)");
    assert_eq!(format!("{:?}", crate::coverage::written(&reserved)), "[B1, B2, B3, B4, B5, B6]");
    let uncite_b1 = |cases: &Path| {
        for file in ["b1/json-usage-error.yaml", "b2/stale-token-refreshes.yaml"] {
            let text = std::fs::read_to_string(cases.join(file)).unwrap().replace("B1, ", "").replace(", B1", "");
            std::fs::write(cases.join(file), text).unwrap();
        }
    };
    let found = report(false, uncite_b1);
    assert!(has(&found.errors, "rule 6: no case cites B1"), "{:?}", found.errors);
}
