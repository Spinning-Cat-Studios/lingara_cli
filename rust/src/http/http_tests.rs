use serde_json::json;

use super::routes::{Fixed, Op, fixed, object};
use super::*;
use crate::fakes::{FakeClock, Script};

fn http(script: &Script) -> Http {
    Http {
        transport: Box::new(script.clone()),
        clock: Box::new(FakeClock),
        bases: Bases { api: "http://api.test/".into(), portal: "http://portal.test".into() },
        user_agent: "lingara-cli/0.0.0 (rust; test)".into(),
        accept_language: Some("en-US".into()),
    }
}

fn busy(script: &Script, expect: &str) {
    script.then(expect, 503, json!(null));
    let last = script.answers.borrow_mut().pop_back().unwrap();
    let mut response = last.1;
    response.headers.push(("retry-after".into(), "1".into()));
    script.answers.borrow_mut().push_back((last.0, response));
}

#[test]
fn urls_fill_vars_and_encode_queries() {
    let h = http(&Script::default());
    let get = object("app", Op::VersionGet);
    assert_eq!(h.url(&get, &[("id", "a b"), ("semver", "1.0.0")], &[]), "http://api.test/platform/objects/a%20b/versions/1.0.0");
    let list = object("app", Op::List);
    assert_eq!(h.url(&list, &[], &[("kind", "app"), ("cursor", "x/y")]), "http://api.test/platform/objects?kind=app&cursor=x%2Fy");
    assert!(h.url(&fixed(Fixed::Authorize), &[], &[]).starts_with("http://portal.test/oauth/authorize"));
}

#[test]
fn b5_headers_and_bodies() {
    let script = Script::default();
    script.then("POST /oauth/token", 200, json!({}));
    let route = fixed(Fixed::Token);
    let mut request = Request::new(&route);
    request.body = Body::Form(vec![("grant_type".into(), "client_credentials".into())]);
    http(&script).send(&request, &Auth::Basic("id", "se:cret")).unwrap();
    let sent = &script.log.borrow()[0];
    let header = |n: &str| sent.headers.iter().find(|(k, _)| k == n).map(|(_, v)| v.clone());
    assert_eq!(header("user-agent").as_deref(), Some("lingara-cli/0.0.0 (rust; test)"));
    assert_eq!(header("accept-language").as_deref(), Some("en-US"));
    assert_eq!(header("authorization").as_deref(), Some("Basic aWQ6c2UlM0FjcmV0"));
    assert_eq!(String::from_utf8_lossy(&sent.body), "grant_type=client_credentials");
}

/// D6: an idempotent request is retried after a 503 with Retry-After; a POST never is.
#[test]
fn only_idempotent_requests_retry() {
    let script = Script::default();
    busy(&script, "GET /platform/objects/x");
    busy(&script, "GET /platform/objects/x");
    script.then("GET /platform/objects/x", 200, json!({}));
    let get = object("app", Op::Get);
    let reply = http(&script).send(&Request { route: &get, vars: &[("id", "x")], query: &[], body: Body::None }, &Auth::None).unwrap();
    assert_eq!((reply.status, script.log.borrow().len()), (200, 3));

    let script = Script::default();
    busy(&script, "POST /platform/objects");
    let create = object("app", Op::Create);
    let reply = http(&script).send(&Request::new(&create), &Auth::None).unwrap();
    assert_eq!((reply.status, script.log.borrow().len()), (503, 1));
}

/// A DELETE replayed after it succeeded answers a coded 404: that is the success.
#[test]
fn a_replayed_delete_that_finds_nothing_succeeded() {
    let script = Script::default();
    busy(&script, "DELETE /platform/objects/x");
    script.then("DELETE /platform/objects/x", 404, json!({ "code": "object_not_found", "error": "gone" }));
    let delete = object("app", Op::Delete);
    let reply = http(&script).send(&Request { route: &delete, vars: &[("id", "x")], query: &[], body: Body::None }, &Auth::None).unwrap();
    assert_eq!(reply.status, 204);
}

/// A connection reset is `unavailable` (B2 rule 7).
#[test]
fn a_connection_failure_is_unavailable() {
    let list = object("app", Op::List);
    let fail = http(&Script::default()).send(&Request::new(&list), &Auth::None).unwrap_err();
    assert_eq!(fail.exit, Exit::Unavailable);
}
