//! D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own
//! command lines. A `refused` verdict stops apply with every refusal named;
//! a `would_replace`, which the token door no longer sends, stops it before
//! any write. A `would_update` webhook carries exactly the flags its
//! `changes` name: `--url`/`--event` (a new version that keeps the endpoint
//! and its secret) and `--enable` (no builder spec disables one).

use serde_json::Value;

use super::plan::{Live, PlanOp, Records};
use super::spec::app_name;

/// An id an argv carries: one already known (a platform object id), or the
/// one an earlier step of this run makes.
#[derive(Debug, Clone, PartialEq)]
pub enum Part {
    Text(String),
    Id(String),
    Made(String),
    /// The app op's manifest, written to a file at run time.
    Manifest(String),
}

/// What apply records for a step's node (16.10.26x D4: runtime ids only).
#[derive(Debug, Clone, PartialEq)]
pub enum Recording {
    RuntimeId,
    /// `minted_with_client`: the secret its client's create minted.
    ClientSecret(String),
    SecretId,
    Unchanged,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Step {
    pub handle: String,
    pub kind: String,
    pub action: &'static str,
    /// Empty: no call.
    pub argv: Vec<Part>,
    pub records: Recording,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Stop {
    /// Every refused op: handle, code, error.
    Refused(Vec<(String, String, String)>),
    Replace(String),
}

fn text(s: &str) -> Part {
    Part::Text(s.to_string())
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect()).unwrap_or_default()
}

fn repeated(flag: &str, values: &[String]) -> Vec<Part> {
    values.iter().flat_map(|v| [text(flag), text(v)]).collect()
}

/// Every step, or why apply must stop before writing anything.
pub fn steps(ops: &[PlanOp], verdicts: &[Value], live: &Live, kept: &Records) -> Result<Vec<Step>, Stop> {
    let verdict_of = |h: &str| verdicts.iter().find(|v| v["handle"].as_str() == Some(h)).cloned().unwrap_or(Value::Null);
    let refused: Vec<(String, String, String)> = verdicts
        .iter()
        .filter(|v| v["verdict"] == "refused")
        .map(|v| (s(&v["handle"]), s(&v["code"]), s(&v["error"])))
        .collect();
    if !refused.is_empty() {
        return Err(Stop::Refused(refused));
    }
    if let Some(v) = verdicts.iter().find(|v| v["verdict"] == "would_replace") {
        return Err(Stop::Replace(s(&v["handle"])));
    }
    Ok(ops.iter().map(|op| step(op, &verdict_of(&op.handle), live, kept, ops)).collect())
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or_default().to_string()
}

/// The client an op points at: made in this run, or a recorded one's
/// platform object id.
fn client_ref(handle: &str, live: &Live, kept: &Records, ops: &[PlanOp]) -> Part {
    let made_here = ops.iter().any(|o| o.handle == handle && o.op == "create");
    let known = kept.get(handle).and_then(|r| live.platform_id("client", r));
    match (made_here, known) {
        (false, Some(id)) => Part::Id(id),
        _ => Part::Made(handle.to_string()),
    }
}

fn step(op: &PlanOp, verdict: &Value, live: &Live, kept: &Records, ops: &[PlanOp]) -> Step {
    let changes = strings(&verdict["changes"]);
    let unchanged = op.op == "update" && changes.is_empty();
    let own = op.id.as_deref().and_then(|r| live.platform_id(&op.kind, r)).map(Part::Id).unwrap_or_else(|| Part::Made(op.handle.clone()));
    let client = op.client.as_deref().map(|h| client_ref(h, live, kept, ops));
    let body = op.body.clone().unwrap_or(Value::Null);
    let (argv, records) = match (op.kind.as_str(), op.op) {
        _ if unchanged => (Vec::new(), Recording::Unchanged),
        ("client", create) => (client_argv(&body, create == "create", own), recording(create)),
        ("secret", _) if verdict["note"] == "minted_with_client" => (Vec::new(), Recording::ClientSecret(op.client.clone().unwrap_or_default())),
        ("secret", _) => (vec![text("oauth_client"), text("secret"), text("create"), client.unwrap_or(Part::Made(String::new()))], Recording::SecretId),
        ("webhook", "create") => (webhook_create(&body, client), Recording::RuntimeId),
        ("webhook", _) => (webhook_update(&body, &changes, own), Recording::Unchanged),
        (_, "create") => (app_create(op, &body, client), Recording::RuntimeId),
        _ => (vec![text("apps"), text("update"), own, text("--manifest"), Part::Manifest(op.handle.clone())], Recording::Unchanged),
    };
    Step { handle: op.handle.clone(), kind: op.kind.clone(), action: if unchanged { "unchanged" } else { op.op }, argv, records }
}

fn recording(op: &str) -> Recording {
    if op == "create" { Recording::RuntimeId } else { Recording::Unchanged }
}

fn client_argv(body: &Value, create: bool, own: Part) -> Vec<Part> {
    let mut argv = if create { vec![text("oauth_client"), text("create")] } else { vec![text("oauth_client"), text("update"), own] };
    argv.extend([text("--name"), text(body["name"].as_str().unwrap_or_default())]);
    argv.extend(repeated("--scope", &strings(&body["scopes"])));
    argv.extend(repeated("--redirect-uri", &strings(&body["redirect_uris"])));
    argv
}

fn webhook_create(body: &Value, client: Option<Part>) -> Vec<Part> {
    let mut argv = vec![text("webhook"), text("outbound"), text("create"), text("--client"), client.unwrap_or(Part::Made(String::new()))];
    argv.extend([text("--url"), text(body["url"].as_str().unwrap_or_default())]);
    argv.extend(repeated("--event", &strings(&body["event_types"])));
    argv
}

fn webhook_update(body: &Value, changes: &[String], own: Part) -> Vec<Part> {
    let mut argv = vec![text("webhook"), text("outbound"), text("update"), own];
    if changes.iter().any(|c| c == "url") {
        argv.extend([text("--url"), text(body["url"].as_str().unwrap_or_default())]);
    }
    if changes.iter().any(|c| c == "event_types") {
        argv.extend(repeated("--event", &strings(&body["event_types"])));
    }
    if changes.iter().any(|c| c == "enabled") {
        argv.push(text("--enable"));
    }
    argv
}

fn app_create(op: &PlanOp, body: &Value, client: Option<Part>) -> Vec<Part> {
    let name = app_name(body, &op.handle);
    vec![text("apps"), text("create"), text(&name), text("--client"), client.unwrap_or(Part::Made(String::new())), text("--manifest"), Part::Manifest(op.handle.clone())]
}

/// One argv as a shell line, ids not yet made as `<handle>`.
pub fn shell_line(argv: &[Part], manifest: &dyn Fn(&str) -> String) -> String {
    let words: Vec<String> = argv
        .iter()
        .map(|p| match p {
            Part::Text(t) | Part::Id(t) => quote(t),
            Part::Made(h) => quote(&format!("<{h}>")),
            Part::Manifest(h) => quote(&manifest(h)),
        })
        .collect();
    format!("lingara {}", words.join(" "))
}

/// POSIX single quotes, only where a word needs them.
pub fn quote(word: &str) -> String {
    let plain = !word.is_empty() && word.chars().all(|c| c.is_ascii_alphanumeric() || "-_./:@=,+".contains(c));
    if plain { word.to_string() } else { format!("'{}'", word.replace('\'', r"'\''")) }
}
