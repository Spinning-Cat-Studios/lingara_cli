use std::path::Path;

use serde_json::{Value, json};

use super::argv::{self, Part, Recording, Step, Stop};
use super::definition::Definition;
use super::plan::{self, Live, Records, Refusal, Row};

fn vectors() -> Vec<Value> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../conformance/vectors/canvas-plans.json");
    let file: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    file["vectors"].as_array().cloned().unwrap()
}

fn records(v: &Value) -> Records {
    v.as_object().into_iter().flatten().map(|(h, r)| (h.clone(), r["id"].as_str().unwrap().to_string())).collect()
}

fn live(v: &Value) -> Live {
    let rows = |k: &str| v[k].as_array().into_iter().flatten().map(Row::read).collect();
    Live { clients: rows("oauth_client"), webhooks: rows("webhook_out"), apps: rows("app") }
}

fn rendered(step: &Step) -> Value {
    let argv: Vec<String> = step
        .argv
        .iter()
        .map(|p| match p {
            Part::Text(t) | Part::Id(t) => t.clone(),
            Part::Made(h) => format!("<{h}>"),
            Part::Manifest(h) => format!("<{h}.manifest>"),
        })
        .collect();
    let records = match &step.records {
        Recording::RuntimeId => "runtime_id",
        Recording::ClientSecret(_) => "client_secret",
        Recording::SecretId => "secret_id",
        Recording::Unchanged => "unchanged",
    };
    json!({ "handle": step.handle, "action": step.action, "records": records, "argv": argv })
}

fn stop_json(stop: &Stop) -> Value {
    match stop {
        Stop::Refused(all) => json!({ "refused": all.iter().map(|(h, _, _)| h).collect::<Vec<_>>() }),
        Stop::Replace(h) => json!({ "replace": h }),
    }
}

/// 16.10.26ad AC8 the plan vectors hold
#[test]
fn the_plan_vectors_hold() {
    let all = vectors();
    assert!(all.len() >= 8, "every rule has its vector");
    for v in all {
        let name = v["name"].as_str().unwrap();
        let def = Definition::read(&v["definition"]);
        let (rows, kept) = (live(&v["live"]), plan::still_live(&def, &records(&v["recorded"]), &live(&v["live"])));
        let ops = match plan::build(&def, &kept) {
            Err(Refusal::Script(handle)) => {
                assert_eq!(v["expect"]["refused"], json!(handle), "{name}");
                continue;
            }
            other => other.unwrap_or_else(|r| panic!("{name}: {r:?}")),
        };
        let kept_json: serde_json::Map<String, Value> = kept.iter().map(|(h, id)| (h.clone(), json!(id))).collect();
        assert_eq!(Value::Object(kept_json), v["expect"]["kept"], "{name}: kept");
        assert_eq!(json!(ops.iter().map(plan::PlanOp::wire).collect::<Vec<_>>()), v["expect"]["ops"], "{name}: ops");
        let Some(verdicts) = v["verdicts"].as_array() else { continue };
        match argv::steps(&ops, verdicts, &rows, &kept) {
            Ok(steps) => assert_eq!(json!(steps.iter().map(rendered).collect::<Vec<_>>()), v["steps"], "{name}: steps"),
            Err(stop) => assert_eq!(stop_json(&stop), v["stop"], "{name}: stop"),
        }
    }
}

#[test]
fn shell_lines_quote_what_needs_it() {
    let parts = [Part::Text("oauth_client".into()), Part::Text("--name".into()), Part::Text("My server's".into()), Part::Made("n1".into())];
    assert_eq!(argv::shell_line(&parts, &|_| String::new()), r"lingara oauth_client --name 'My server'\''s' '<n1>'");
}
