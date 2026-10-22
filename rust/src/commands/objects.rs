//! The `/platform/objects` family every kind shares (16.10.26v §8; D2):
//! list every page, read, rename, create and publish, the body update that
//! respects the one editable version, delete with the typed email, and the
//! one-time secret mint.

use serde_json::{Value, json};

use crate::app::Ctx;
use crate::exit::Fail;
use crate::http::routes::{Op, Route, object};
use crate::http::{Body, Request};

/// A CLI word and the 16.10.26v kind behind it.
#[derive(Debug, Clone, Copy)]
pub struct Kind {
    /// `routes.toml`'s word: `webhook_outbound`.
    pub word: &'static str,
    /// The platform kind: `webhook_out`.
    pub kind: &'static str,
    /// `{kind}` in `confirm_yes_no`.
    pub label: &'static str,
}

pub const OAUTH_CLIENT: Kind = Kind { word: "oauth_client", kind: "oauth_client", label: "OAuth client" };
pub const APP: Kind = Kind { word: "app", kind: "app", label: "app" };
pub const EMBED: Kind = Kind { word: "embed", kind: "embed", label: "embed" };
pub const WEBHOOK_INBOUND: Kind = Kind { word: "webhook_inbound", kind: "webhook_in", label: "inbound webhook" };
pub const WEBHOOK_OUTBOUND: Kind = Kind { word: "webhook_outbound", kind: "webhook_out", label: "outbound webhook" };

fn send(ctx: &mut Ctx, route: &Route, vars: &[(&str, &str)], body: Body) -> Result<Value, Fail> {
    ctx.call(&Request { route, vars, query: &[], body })
}

/// Every item of every page, followed by `next_cursor` (B1).
pub fn list(ctx: &mut Ctx, k: &Kind) -> Result<Vec<Value>, Fail> {
    let route = object(k.word, Op::List);
    let (mut items, mut cursor) = (Vec::new(), None::<String>);
    loop {
        let mut query = vec![("kind", k.kind)];
        if let Some(c) = cursor.as_deref() {
            query.push(("cursor", c));
        }
        let page = ctx.call(&Request { route: &route, vars: &[], query: &query, body: Body::None })?;
        items.extend(page["items"].as_array().cloned().unwrap_or_default());
        match page["next_cursor"].as_str() {
            Some(next) => cursor = Some(next.to_string()),
            None => return Ok(items),
        }
    }
}

pub fn get(ctx: &mut Ctx, k: &Kind, id: &str) -> Result<Value, Fail> {
    send(ctx, &object(k.word, Op::Get), &[("id", id)], Body::None)
}

pub fn rename(ctx: &mut Ctx, k: &Kind, id: &str, name: &str) -> Result<Value, Fail> {
    send(ctx, &object(k.word, Op::Rename), &[("id", id)], Body::Json(json!({ "name": name })))
}

/// The object and its first draft: `{kind, name, parent_id?, body}`.
pub fn create(ctx: &mut Ctx, k: &Kind, name: &str, parent: Option<&str>, body: Value) -> Result<Value, Fail> {
    let mut request = json!({ "kind": k.kind, "name": name, "body": body });
    if let Some(p) = parent {
        request["parent_id"] = json!(p);
    }
    send(ctx, &object(k.word, Op::Create), &[], Body::Json(request))
}

pub fn version_op(ctx: &mut Ctx, k: &Kind, op: Op, id: &str, semver: &str) -> Result<Value, Fail> {
    send(ctx, &object(k.word, op), &[("id", id), ("semver", semver)], Body::None)
}

/// A private object skips review, so a version is published at once
/// (16.10.26v §4). A public one stays a draft for `apps submit`.
pub fn publish_if_private(ctx: &mut Ctx, k: &Kind, object: &Value, semver: &str) -> Result<Option<Value>, Fail> {
    if object["visibility"].as_str() == Some("public") || semver.is_empty() {
        return Ok(None);
    }
    version_op(ctx, k, Op::Publish, object["id"].as_str().unwrap_or_default(), semver).map(Some)
}

/// D2's body update: overlay the flags on the one editable version
/// (`version_put` at its `draft_version`), else on the serving (or highest)
/// version as a new one (`version_create`). Always a whole body.
pub fn body_update(ctx: &mut Ctx, k: &Kind, id: &str, overlay: &dyn Fn(&mut Value)) -> Result<Value, Fail> {
    let current = get(ctx, k, id)?;
    let versions = current["versions"].as_array().cloned().unwrap_or_default();
    let editable = versions.iter().find(|v| matches!(v["state"].as_str(), Some("draft" | "changes_requested")));
    let answer = match editable.and_then(|v| v["semver"].as_str()) {
        Some(semver) => {
            let version = version_op(ctx, k, Op::VersionGet, id, semver)?;
            let body = overlaid(&version["body"], overlay);
            let edit = json!({ "body": body, "draft_version": version["draft_version"] });
            send(ctx, &object(k.word, Op::VersionPut), &[("id", id), ("semver", semver)], Body::Json(edit))?
        }
        None => {
            let base = current["serving_version"].as_str().or_else(|| versions.first().and_then(|v| v["semver"].as_str()));
            let start = match base {
                Some(semver) => version_op(ctx, k, Op::VersionGet, id, semver)?["body"].clone(),
                None => json!({}),
            };
            let made = json!({ "body": overlaid(&start, overlay) });
            send(ctx, &object(k.word, Op::VersionCreate), &[("id", id)], Body::Json(made))?
        }
    };
    let semver = answer["semver"].as_str().unwrap_or_default().to_string();
    Ok(publish_if_private(ctx, k, &current, &semver)?.unwrap_or(answer))
}

fn overlaid(body: &Value, overlay: &dyn Fn(&mut Value)) -> Value {
    let mut body = if body.is_object() { body.clone() } else { json!({}) };
    overlay(&mut body);
    body
}

/// The destructive request: the canonical email in its body (B3.6).
pub fn delete(ctx: &mut Ctx, k: &Kind, id: &str, version: Option<&str>, email: &str) -> Result<Value, Fail> {
    let confirm = Body::Json(json!({ "confirm": email }));
    match version {
        Some(semver) => send(ctx, &object(k.word, Op::DeleteVersion), &[("id", id), ("semver", semver)], confirm),
        None => send(ctx, &object(k.word, Op::Delete), &[("id", id)], confirm),
    }
}

/// `{secret_id, prefix, secret, expires_at}`, revealed once.
pub fn secret_create(ctx: &mut Ctx, k: &Kind, id: &str) -> Result<Value, Fail> {
    send(ctx, &object(k.word, Op::SecretCreate), &[("id", id)], Body::None)
}

/// A `get` printed: the server's JSON, or B1's `FIELD  VALUE` table.
pub fn print_get(ctx: &mut Ctx, k: &Kind, id: &str) -> Result<(), Fail> {
    let value = get(ctx, k, id)?;
    ctx.out.value(&value);
    Ok(())
}
