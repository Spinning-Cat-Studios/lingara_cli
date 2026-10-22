//! D6's invariants, judged on evidence built by hand, and the mock's own
//! answers to I2 and B5's header matchers.

use std::io::{Read, Write};
use std::time::{Duration, Instant};

use crate::case::{self, Case};
use crate::case_tests::{real_routes, real_schema};
use crate::invariants::{Evidence, check};
use crate::mock::http::HttpRequest;
use crate::mock::{Logged, Mock};
use crate::spawn::Outcome;

const UA: &str = "lingara-cli/0.1.0 (rust; macos)";
const DELETE_PATH: &str = "/platform/objects/oc_1";
const GOOD_BODY: &str = r#"{"confirm":"dev@conformance.invalid"}"#;

fn delete_case(confirm: &str, tty: bool, argv: &str) -> Case {
    let interact = if tty {
        "tty: true\ninteract: [ { expect: { contains: \"?\" }, send: \"y\\n\" }, { expect: { contains: \":\" }, send: \"x\\n\" } ]\n"
    } else {
        ""
    };
    case::parse(&format!(
        "id: b3.t\ntitle: t\nbehaviours: [B3]\ncommand: oauth_client delete\nconfirm: {confirm}\n\
         argv: [client, delete, oc_1{argv}]\ncredentials: stored\n{interact}expect: {{ exit: ok }}\n"
    ))
    .unwrap()
}

fn logged(method: &str, path: &str, body: &str, at: Instant) -> Logged {
    let headers = vec![("user-agent".to_string(), UA.to_string())];
    let request = HttpRequest { method: method.into(), path: path.into(), query: None, headers, body: body.as_bytes().to_vec() };
    Logged { request, at, answer: None, mismatch: None }
}

fn judged(case: &Case, outcome: &Outcome, log: &[Logged]) -> Vec<String> {
    let (schema, routes) = (real_schema(), real_routes());
    check(&Evidence { case, schema: &schema, routes: &routes, outcome, log })
}

fn ok() -> Outcome {
    Outcome { exit: Some(0), ..Outcome::default() }
}

fn i1(found: &[String]) -> bool {
    found.iter().any(|m| m.starts_with("I1"))
}

/// 16.10.26ac AC3
#[test]
fn no_destructive_call_without_confirmation() {
    let now = Instant::now();
    let delete = [logged("DELETE", DELETE_PATH, GOOD_BODY, now)];
    assert!(i1(&judged(&delete_case("non_tty_flags", false, ""), &ok(), &delete)), "a pipe without flags");
    let wrong = delete_case("non_tty_flags", false, ", -y, --confirm, other@else.invalid");
    assert!(i1(&judged(&wrong, &ok(), &delete)), "flags with another email");

    let typed = Outcome { last_send: Some(now + Duration::from_millis(5)), sends: 2, ..ok() };
    assert!(i1(&judged(&delete_case("tty_ok", true, ""), &typed, &delete)), "before the last send");
    let after = Outcome { last_send: Some(now - Duration::from_millis(5)), sends: 2, ..ok() };
    assert!(judged(&delete_case("tty_ok", true, ""), &after, &delete).is_empty(), "after the last send");

    let flagged = delete_case("non_tty_flags", false, ", -y, --confirm, Dev@Conformance.invalid ");
    for body in ["", r#"{"confirm":"Dev@Conformance.invalid "}"#, r#"{"email":"dev@conformance.invalid"}"#] {
        let bad = [logged("DELETE", DELETE_PATH, body, now)];
        assert!(i1(&judged(&flagged, &ok(), &bad)), "body {body:?}");
    }
    let lookup_then_delete = [logged("GET", "/platform/objects", "", now), logged("DELETE", DELETE_PATH, GOOD_BODY, now)];
    assert_eq!(judged(&flagged, &ok(), &lookup_then_delete), Vec::<String>::new());
}

/// 16.10.26ac AC4
#[test]
fn refusal_sends_nothing_and_leaks_nothing() {
    let read = [logged("GET", DELETE_PATH, "", Instant::now())];
    let refused = Outcome { exit: Some(6), ..Outcome::default() };
    let found = judged(&delete_case("non_tty_refused", false, ""), &refused, &read);
    assert!(found.iter().any(|m| m.contains("non_tty_refused case; expected none")), "{found:?}");

    let list = case::parse("id: b1.t\ntitle: t\nbehaviours: [B1]\ncommand: apps list\nargv: [apps, list, --json]\nexpect: { exit: ok }\n").unwrap();
    let two = Outcome { stdout: b"{}\n[]\n".to_vec(), ..ok() };
    assert!(judged(&list, &two, &[]).iter().any(|m| m.starts_with("I4")));
    let one = Outcome { stdout: b"[]\n".to_vec(), ..ok() };
    assert!(judged(&list, &one, &[]).is_empty());

    let mut issued = logged("POST", "/oauth/token", "", Instant::now());
    issued.answer = Some(serde_json::json!({ "access_token": "lgr_at_conformance_new", "refresh_token": "rt_9" }));
    let leaky = Outcome { stderr: "debug: token rt_9".into(), ..one };
    let found = judged(&list, &leaky, &[issued]);
    assert!(found.iter().any(|m| m.starts_with("I6") && m.contains("rt_9")), "{found:?}");
}

/// Sends one raw request to the mock and returns its status.
fn send(url: &str, head: &str) -> u16 {
    let mut stream = std::net::TcpStream::connect(url.trim_start_matches("http://")).unwrap();
    stream.write_all(head.as_bytes()).unwrap();
    let mut answer = String::new();
    let _ = stream.read_to_string(&mut answer);
    answer.split(' ').nth(1).and_then(|s| s.parse().ok()).unwrap_or(0)
}

/// 16.10.26ac AC11
#[test]
fn every_request_is_expected_and_identifies_itself() {
    let case = case::parse(
        "id: b2.t\ntitle: t\nbehaviours: [B2, B5]\ncommand: apps list\nargv: [apps, list]\nexpect: { exit: ok }\n\
         exchanges: { items: [ { route: app.list, request: { headers: { accept-language: { equals: en-US } } }, response: { status: 200, json: { items: [] } } } ] }\n",
    )
    .unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let mock = rt.block_on(Mock::start(&case, &real_routes())).unwrap();
    let french = send(&mock.url, &format!("GET /platform/objects?kind=app HTTP/1.1\r\nuser-agent: {UA}\r\naccept-language: fr-FR\r\n\r\n"));
    let stray = send(&mock.url, "GET /platform/nope HTTP/1.1\r\nuser-agent: curl/8\r\n\r\n");
    let finished = mock.finish();
    assert_eq!((french, stray), (599, 599), "neither request is one the case expects");
    let found = judged(&case, &ok(), &finished.log);
    assert!(found.iter().any(|m| m.starts_with("I2") && m.contains("accept-language")), "{found:?}");
    assert!(found.iter().any(|m| m.starts_with("I2") && m.contains("/platform/nope")), "{found:?}");
    assert!(found.iter().any(|m| m.starts_with("I3") && m.contains("curl/8")), "{found:?}");
    assert_eq!(finished.unconsumed.len(), 1, "the list was never answered");
}
