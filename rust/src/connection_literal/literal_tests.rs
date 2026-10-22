//! The literal reader against spec/connection-fixtures/, which every kit's
//! reader is held to (ADR 16.10.26ae D2).

use std::path::PathBuf;

use serde_json::Value;

use super::{Refusal, app_manifest, statements};

fn fixtures(kind: &str) -> Vec<(String, PathBuf)> {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spec/connection-fixtures").join(kind);
    let mut out: Vec<(String, PathBuf)> = std::fs::read_dir(&dir)
        .expect("the fixtures directory")
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "js"))
        .map(|p| (p.file_stem().unwrap().to_string_lossy().to_string(), p))
        .collect();
    out.sort();
    out
}

fn expected(path: &std::path::Path) -> Option<Value> {
    let text = std::fs::read_to_string(path.with_extension("json")).ok()?;
    Some(serde_json::from_str(&text).expect("the expectation is JSON"))
}

/// 16.10.26ae AC5: every valid fixture reads, and converts to the wire
/// manifest `lingara.manifest` would write, listing fields included.
#[test]
fn every_valid_fixture_converts_to_its_wire_manifest() {
    for (name, path) in fixtures("valid") {
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(statements(&text).is_ok(), "{name}: {:?}", statements(&text));
        match expected(&path) {
            Some(want) => {
                let got = app_manifest(&text).unwrap_or_else(|e| panic!("{name}: {e:?}"));
                assert_eq!(serde_json::to_string(&got).unwrap(), serde_json::to_string(&want).unwrap(), "{name}");
            }
            None => assert!(app_manifest(&text).is_err(), "{name} has no app"),
        }
    }
}

/// 16.10.26ae AC5: every refused fixture is refused with its code, at its
/// position; a scripted statement is named.
#[test]
fn every_refused_fixture_is_refused_where_it_says() {
    for (name, path) in fixtures("refused") {
        let text = std::fs::read_to_string(&path).unwrap();
        let want = expected(&path).expect("a refused fixture has its .json");
        let got = app_manifest(&text).expect_err(&name);
        match got {
            Refusal::Scripted(handle) => {
                assert_eq!(want["code"], "connection_scripted", "{name}");
                assert_eq!(want["statement"], handle.as_str(), "{name}");
            }
            Refusal::At(code, at) => {
                assert_eq!((want["code"].as_str(), want["line"].as_u64(), want["column"].as_u64()), (Some(code), Some(at.line as u64), Some(at.column as u64)), "{name}");
            }
        }
    }
}

#[test]
fn a_refusal_prints_its_position_and_a_script_names_its_statement() {
    let fail = Refusal::At("key", super::Pos { line: 3, column: 40 }).fail("lingara/connection.js");
    assert!(fail.message.starts_with("lingara/connection.js:3:40: ") && fail.code == "connection_literal");
    let scripted = Refusal::Scripted("hooks".into()).fail("lingara/connection.js");
    assert_eq!(scripted.exit, crate::exit::Exit::ConnectionScripted);
    assert!(scripted.message.contains("hooks"));
}
