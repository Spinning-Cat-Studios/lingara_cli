//! The closed case schema and `routes.toml` (ADR 16.10.26ac D3, D4).

use std::path::{Path, PathBuf};

use crate::case::schema::{self, Schema};
use crate::case;
use crate::routes::{self, Routes};

pub fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn real_schema() -> Schema {
    schema::load(&repo().join("spec/cli.schema.json")).expect("the committed schema loads")
}

pub fn real_routes() -> Routes {
    routes::load(&repo().join("conformance/routes.toml")).expect("the committed routes.toml loads")
}

const BASE: &str = "id: b2.probe
title: t
behaviours: [B2]
command: apps list
argv: [apps, list]
credentials: stored
expect: { exit: ok }
";

/// Writes `yaml` as `cases/b2/probe.yaml` and returns every refusal: the
/// load's, else `check`'s.
fn refusals(yaml: &str) -> Vec<String> {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("b2")).unwrap();
    std::fs::write(dir.path().join("b2/probe.yaml"), yaml).unwrap();
    match case::load_dir(dir.path()) {
        Err(errors) => errors,
        Ok(cases) => cases.iter().flat_map(|l| case::check::check(l, &real_schema(), &real_routes())).collect(),
    }
}

fn assert_refused(yaml: &str, needle: &str) {
    let found = refusals(yaml);
    assert!(found.iter().any(|e| e.contains(needle)), "expected a refusal containing {needle:?}, got {found:?}");
    assert!(found.iter().all(|e| e.contains("probe.yaml")), "every refusal names its file: {found:?}");
}

/// 16.10.26ac AC1
#[test]
fn case_schema_and_routes_are_closed() {
    let committed = case::load_dir(&repo().join("conformance/cases")).expect("every committed case parses");
    let (schema, routes) = (real_schema(), real_routes());
    let refused: Vec<String> = committed.iter().flat_map(|l| case::check::check(l, &schema, &routes)).collect();
    assert!(refused.is_empty(), "{refused:?}");
    assert!(refusals(BASE).is_empty(), "the base case is valid: {:?}", refusals(BASE));

    assert_refused(&format!("{BASE}nope: 1\n"), "unknown field `nope`");
    assert_refused(&BASE.replace("exit: ok", "exit: exploded"), "b2.probe (");
    assert_refused(&BASE.replace("exit: ok", "exit: exploded"), "not a D3 exit name");
    assert_refused(&format!("{BASE}exchanges: {{ items: [ {{ route: app.nope, response: {{ status: 200 }} }} ] }}\n"), "unknown route");
    assert_refused(&format!("{BASE}tty: true\nstdin: \"\"\n"), "`stdin` is for a non-tty case only");
    assert_refused(&format!("{BASE}interact: [ {{ expect: {{ contains: x }}, send: \"y\\n\" }} ]\n"), "tty case only");
    assert_refused(&format!("{BASE}confirm: tty_ok\n"), "dangerous command only");
    assert_refused(&format!("{BASE}env: {{ LINGARA_API_URL: x }}\n"), "one the runner sets itself");
    assert_refused(&format!("{BASE}env: {{ HOME: x }}\n"), "LINGARA_* names only");
    assert_refused(&BASE.replace("command: apps list\n", ""), "so `command` is required");
    let unresolved = BASE.replace("command: apps list\n", "").replace("[apps, list]", "[nope]");
    assert!(refusals(&unresolved.replace("exit: ok", "exit: usage")).is_empty());
    assert_refused(&unresolved, "must expect `usage`");
    assert_refused(&BASE.replace("[apps, list]", "[apps, get, x]"), "does not resolve to `apps list`");
    assert_refused(&BASE.replace("id: b2.probe", "id: b2.other"), "must be `b2.probe`");

    let missing = std::fs::read_to_string(repo().join("conformance/routes.toml"))
        .unwrap()
        .replace(r#""oauth_client.delete", "oauth_client.delete_version","#, r#""oauth_client.delete","#);
    let e = routes::parse(&missing).unwrap_err();
    assert!(e.contains("oauth_client.delete_version") && e.contains("missing from `destructive`"), "{e}");
}

/// A route's `?…` is split off before matching (D3), and a `{var}` segment
/// fits any one segment.
#[test]
fn routes_split_queries_and_fit_templates() {
    let routes = real_routes();
    let list = routes.get("app.list").unwrap();
    assert_eq!((list.path.as_str(), list.query.get("kind").map(String::as_str)), ("/platform/objects", Some("app")));
    assert!(routes.is_destructive("DELETE", "/platform/objects/abc"));
    assert!(!routes.is_destructive("GET", "/platform/objects/abc"));
    // ADR 16.10.26ad D2: the secret revoke is destructive; minting one is not.
    assert!(routes.is_destructive("DELETE", "/platform/objects/abc/secrets/x"));
    assert!(!routes.is_destructive("POST", "/platform/objects/abc/secrets"));
}
