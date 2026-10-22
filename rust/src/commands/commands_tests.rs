use serde_json::{Value, json};

use super::objects::{self, APP};
use super::oauth_client;
use crate::cli::client::CreateArgs;
use crate::exit::Exit;
use crate::fakes::{Script, app, pipe, with_ctx};

const ID: &str = "7d1e2f30-0000-4000-8000-000000000001";

fn object(versions: Value, visibility: &str) -> Value {
    json!({ "id": ID, "kind": "app", "name": "Flashcards", "visibility": visibility, "versions": versions, "serving_version": "1.0.0" })
}

fn manifest(body: &mut Value) {
    body["manifest"] = json!({ "name": "v2" });
}

/// 16.10.26ad AC17 updates respect the one draft and creates never lose a secret
#[test]
fn updates_respect_the_one_draft_and_creates_never_lose_a_secret() {
    // An editable version exists: it is read and PUT back at its draft_version, then published.
    let script = Script::default();
    let versions = json!([{ "semver": "1.1.0", "state": "draft" }, { "semver": "1.0.0", "state": "published" }]);
    script
        .then(&format!("GET /platform/objects/{ID}"), 200, object(versions, "private"))
        .then(&format!("GET /platform/objects/{ID}/versions/1.1.0"), 200, json!({ "semver": "1.1.0", "draft_version": 4, "body": { "manifest": {}, "icon_key": "k" } }))
        .then(&format!("PUT /platform/objects/{ID}/versions/1.1.0"), 200, json!({ "semver": "1.1.0" }))
        .then(&format!("POST /platform/objects/{ID}/versions/1.1.0/publish"), 200, json!({ "version": { "semver": "1.1.0" } }));
    let mut a = app(&script, pipe());
    let (result, _, _) = with_ctx(&mut a, true, |ctx| objects::body_update(ctx, &APP, ID, &manifest));
    assert!(result.is_ok(), "{result:?}");
    assert_eq!(script.body(2), json!({ "body": { "manifest": { "name": "v2" }, "icon_key": "k" }, "draft_version": 4 }));

    // None editable: the serving version is the start of a new one; a public object stays a draft.
    let script = Script::default();
    script
        .then(&format!("GET /platform/objects/{ID}"), 200, object(json!([{ "semver": "1.0.0", "state": "published" }]), "public"))
        .then(&format!("GET /platform/objects/{ID}/versions/1.0.0"), 200, json!({ "semver": "1.0.0", "body": { "manifest": {} } }))
        .then(&format!("POST /platform/objects/{ID}/versions"), 201, json!({ "semver": "1.1.0" }));
    let mut a = app(&script, pipe());
    let (result, _, _) = with_ctx(&mut a, true, |ctx| objects::body_update(ctx, &APP, ID, &manifest));
    assert!(result.is_ok(), "{result:?}");
    assert_eq!(script.body(2), json!({ "body": { "manifest": { "name": "v2" } } }));
    assert_eq!(script.sent().len(), 3, "no publish for a public app");

    // A create whose final read fails after the secret: the secret is on stdout, the client named on stderr.
    let script = Script::default();
    script
        .then("POST /platform/objects", 201, json!({ "id": "oc1", "visibility": "private", "version": { "semver": "1.0.0" } }))
        .then("POST /platform/objects/oc1/versions/1.0.0/publish", 200, json!({}))
        .then("POST /platform/objects/oc1/secrets", 201, json!({ "secret_id": "s1", "secret": "lgr_cs_once" }))
        .then("GET /platform/objects/oc1", 503, json!(null));
    let mut a = app(&script, pipe());
    let args = CreateArgs { name: "My server".into(), client_type: "confidential".into(), billing_mode: "allowance".into(), ..Default::default() };
    let (result, stdout, _) = with_ctx(&mut a, true, |ctx| oauth_client::create(ctx, &args));
    let fail = result.unwrap_err();
    assert_eq!((fail.exit, fail.made.len(), fail.made[0].id.as_str()), (Exit::Unavailable, 1, "oc1"));
    let printed: Value = serde_json::from_str(&stdout).unwrap();
    assert_eq!(printed["secret"], json!({ "id": "s1", "value": "lgr_cs_once" }));
}

/// D3: a second 401 after one refresh is auth_required, with exactly one token request.
#[test]
fn a_second_401_is_auth_required_after_one_refresh() {
    let script = Script::default();
    script
        .then("GET /platform/objects?kind=app", 401, json!(null))
        .then("POST /oauth/token", 200, json!({ "access_token": "lgr_at_new", "refresh_token": "lgr_rt_new" }))
        .then("GET /platform/objects?kind=app", 401, json!(null));
    let mut a = app(&script, pipe());
    let (result, _, _) = with_ctx(&mut a, false, |ctx| objects::list(ctx, &APP));
    assert_eq!(result.unwrap_err().exit, Exit::AuthRequired);
    assert_eq!(script.sent().iter().filter(|s| s.starts_with("POST /oauth/token")).count(), 1);
}
