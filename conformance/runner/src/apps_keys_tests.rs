//! The case-schema amendment ADR 16.10.26ae D11 makes: its keys belong to
//! the `apps` group alone, the dev-session model answers the five
//! `app_dev.*` routes, `expect.calls` reads its log as an ordered
//! subsequence, and `signal` fires on the message it names.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::case::apps::{CallMatch, CountRange, MockSeed, RenewAnswer};
use crate::case::{self, Loaded};
use crate::case_tests::{real_routes, real_schema};
use crate::judge::apps as judges;
use crate::mock::http::HttpRequest;
use crate::mock::platform_dev::DevModel;
use crate::spawn::{self, Job, Limits, SignalPlan};

fn loaded(group: &str, extra: &str) -> Loaded {
    let yaml = format!("id: {group}.probe\ntitle: t\nbehaviours: [B5]\ncommand: apps status\nargv: [apps, status]\n{extra}expect: {{ exit: ok }}\n");
    Loaded { case: case::parse(&yaml).expect("the probe parses"), path: PathBuf::from(format!("{group}/probe.yaml")) }
}

fn refusals(group: &str, extra: &str) -> Vec<String> {
    case::check::check(&loaded(group, extra), &real_schema(), &real_routes())
}

fn request(method: &str, path: &str, body: Value) -> HttpRequest {
    let body = if body.is_null() { Vec::new() } else { body.to_string().into_bytes() };
    HttpRequest { method: method.into(), path: path.into(), query: None, headers: Vec::new(), body }
}

const APP: &str = "/platform/objects/7d1e2f30-0000-4000-8000-000000000001";

/// 16.10.26ae AC18: the D11 keys are refused outside the `apps` group and
/// `exit_timeout_secs` outside 30–90; `mock: platform_dev` answers the five
/// `app_dev.*` routes from its model, whose log `expect.calls` judges; and
/// `signal.after` fires on the named message.
#[test]
fn apps_keys_are_group_scoped_and_drive_the_dev_mock() {
    for key in ["mock: platform_dev\n", "project: dev-basic\n", "tunnel: fake\n", "exit_timeout_secs: 40\n"] {
        assert!(refusals("b5", key).iter().any(|e| e.contains("is for an `apps` case only")), "{key}");
        assert!(refusals("apps", key).is_empty(), "{key}: {:?}", refusals("apps", key));
    }
    assert!(refusals("apps", "exit_timeout_secs: 20\n").iter().any(|e| e.contains("outside 30–90")));
    assert!(refusals("apps", "exit_timeout_secs: 91\n").iter().any(|e| e.contains("outside 30–90")));
    dev_model_answers_the_group();
    signal_fires_on_the_message();
}

fn dev_model_answers_the_group() {
    let seed = MockSeed { renew: vec![RenewAnswer::Ok, RenewAnswer::NotLive], version_conflicts: 1, ..MockSeed::default() };
    let mut model = DevModel::new(&seed, &real_routes());
    let open = model.answer(&request("POST", &format!("{APP}/dev/session"), json!({ "take_over": false }))).unwrap();
    assert_eq!(open.0, 201);
    let sid = open.1.unwrap()["session_id"].as_str().unwrap().to_string();
    assert_eq!(model.answer(&request("POST", &format!("{APP}/dev/session"), json!({}))).unwrap().0, 409, "one session per dev version");
    assert_eq!(model.answer(&request("GET", &format!("{APP}/dev"), Value::Null)).unwrap().0, 200);
    let patch = json!({ "render_url": "https://a.trycloudflare.com/", "session_id": sid, "draft_version": 1 });
    assert_eq!(model.answer(&request("PATCH", &format!("{APP}/dev"), patch.clone())).unwrap().0, 409, "the seeded version_conflict");
    assert_eq!(model.answer(&request("PATCH", &format!("{APP}/dev"), patch.clone())).unwrap().0, 200);
    let renew = format!("{APP}/dev/session/{sid}/renew");
    assert_eq!(model.answer(&request("POST", &renew, Value::Null)).unwrap().0, 200);
    assert_eq!(model.answer(&request("POST", &renew, Value::Null)).unwrap().0, 409, "the scripted not_live");
    assert_eq!(model.answer(&request("DELETE", &format!("{APP}/dev/session/{sid}"), Value::Null)).unwrap().0, 409, "already over");
    assert!(model.answer(&request("GET", APP, Value::Null)).is_none(), "outside the group: exchanges answer it");
    let call = |route: &str, json: Option<Value>, count: Option<CountRange>| CallMatch { route: route.into(), json, count };
    let want = [
        call("app_dev.session_open", Some(json!({ "take_over": false })), None),
        call("app_dev.patch", Some(patch), Some(CountRange { min: 2, max: Some(2) })),
        call("app_dev.session_renew", None, Some(CountRange { min: 2, max: Some(2) })),
    ];
    assert_eq!(judges::calls(&want, &model.calls, &[]), Vec::<String>::new());
    let absent = [call("app_dev.session_end", None, Some(CountRange { min: 0, max: Some(0) }))];
    assert!(!judges::calls(&absent, &model.calls, &[]).is_empty(), "a call that came fails count 0");
}

fn signal_fires_on_the_message() {
    let command = vec!["sh".to_string(), "-c".into(), "echo 'waiting'; echo 'luba → https://x (dev version; only you can install it)' >&2; exec sleep 20".into()];
    let env: BTreeMap<String, String> = std::iter::once(("PATH".to_string(), std::env::var("PATH").unwrap_or_default())).collect();
    let template = &real_schema().messages["apps_dev_ready"];
    let plan = SignalPlan { fragments: crate::judge::fragments_of(template, &Default::default()), count: 1, signal: nix::libc::SIGINT };
    let cwd: &Path = Path::new(".");
    let job = Job { command: &command, argv: &[], env: &env, cwd, tty: false, stdin: None, steps: &[], limits: Limits::default(), signal: Some(plan) };
    let started = Instant::now();
    let outcome = spawn::run(&job);
    assert!(started.elapsed() < Duration::from_secs(10), "the signal ended the child early");
    assert_eq!(outcome.exit, None, "it died of the signal");
}
