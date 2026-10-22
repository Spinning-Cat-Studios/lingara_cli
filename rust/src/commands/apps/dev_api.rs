//! The five `app_dev.*` routes (Backend 16.10.26v §12; ADR 16.10.26ae
//! R1–R3), one function each, plus the read-then-patch every writer of the
//! dev version uses: a `409 version_conflict` re-reads once and retries, and
//! a second one is the server's `refused`.

use serde_json::{Value, json};

use crate::app::Ctx;
use crate::exit::Fail;
use crate::http::routes::{Fixed, fixed};
use crate::http::{Body, Request};

/// What an open answers: the session, and the secrets it minted, which
/// live only in this process's memory and the app's environment (D5).
#[derive(Debug, Clone, Default)]
pub struct Session {
    pub id: String,
    pub lease_secs: u64,
    pub expires_at: String,
    pub app_secret: String,
    pub client_secret: Option<String>,
}

fn call(ctx: &mut Ctx, route: Fixed, vars: &[(&str, &str)], body: Body) -> Result<Value, Fail> {
    let route = fixed(route);
    ctx.call(&Request { route: &route, vars, query: &[], body })
}

/// `GET …/dev`: the manifest, `render_url`, `draft_version`, the session.
pub fn get(ctx: &mut Ctx, id: &str) -> Result<Value, Fail> {
    call(ctx, Fixed::AppDevGet, &[("id", id)], Body::None)
}

pub fn patch(ctx: &mut Ctx, id: &str, body: Value) -> Result<Value, Fail> {
    call(ctx, Fixed::AppDevPatch, &[("id", id)], Body::Json(body))
}

/// Reads `draft_version`, then patches `build(draft_version)`; one re-read
/// and retry on `version_conflict`.
pub fn patch_with_retry(ctx: &mut Ctx, id: &str, build: impl Fn(Value) -> Value) -> Result<Value, Fail> {
    let draft = get(ctx, id)?["draft_version"].clone();
    match patch(ctx, id, build(draft)) {
        Err(fail) if fail.code == "version_conflict" => {
            let draft = get(ctx, id)?["draft_version"].clone();
            patch(ctx, id, build(draft))
        }
        other => other,
    }
}

/// `POST …/dev/session {take_over, credentials}`.
pub fn open(ctx: &mut Ctx, id: &str, take_over: bool, client: bool) -> Result<Session, Fail> {
    let mut body = json!({ "take_over": take_over });
    if client {
        body["credentials"] = json!(["client"]);
    }
    let answer = call(ctx, Fixed::AppDevSessionOpen, &[("id", id)], Body::Json(body))?;
    let text = |v: &Value| v.as_str().unwrap_or_default().to_string();
    Ok(Session {
        id: text(&answer["session_id"]),
        lease_secs: answer["lease_secs"].as_u64().filter(|s| *s > 0).unwrap_or(300),
        expires_at: text(&answer["expires_at"]),
        app_secret: text(&answer["app_secret"]["secret"]),
        client_secret: answer["client_secret"]["secret"].as_str().map(str::to_string),
    })
}

pub fn renew(ctx: &mut Ctx, id: &str, session_id: &str) -> Result<Value, Fail> {
    call(ctx, Fixed::AppDevSessionRenew, &[("id", id), ("session_id", session_id)], Body::None)
}

/// `DELETE …/dev/session/{sid}`: parks, keeps `render_url`, revokes the
/// session's credentials.
pub fn end(ctx: &mut Ctx, id: &str, session_id: &str) -> Result<Value, Fail> {
    call(ctx, Fixed::AppDevSessionEnd, &[("id", id), ("session_id", session_id)], Body::None)
}
