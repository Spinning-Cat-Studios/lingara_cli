//! D7 steps 4–7. Each step's argv is dispatched **in-process** through
//! `App::run`, `main`'s own entry point, with `--json`, so no credential
//! crosses a process boundary and no second token exchange happens. Apply
//! records each object's `runtime_id` (a secret's id), never its platform
//! object id, then activates `complete`, or `partial` at the first failure.

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::path::PathBuf;

use serde_json::{Value, json};

use super::apply::Planned;
use super::argv::{Part, Recording, Step};
use super::plan::Records;
use crate::app::Ctx;
use crate::assets::message;
use crate::commands::objects::{self, APP, Kind, OAUTH_CLIENT, WEBHOOK_OUTBOUND};
use crate::exit::{Exit, Fail};
use crate::http::routes::{Fixed, fixed};
use crate::http::{Body, Request};
use crate::output::table::render;

/// A `runtime_id` still `null` is re-read once a second, ten times (D7).
const PUBLISH_POLLS: usize = 10;

#[derive(Default)]
struct State {
    recorded: Records,
    /// Handle → the platform object id an earlier step made.
    made: BTreeMap<String, String>,
    /// Handle → the secret its client's create minted.
    minted: BTreeMap<String, String>,
    secrets: Vec<Value>,
    rows: Vec<Vec<String>>,
}

fn kind_of(node_kind: &str) -> Kind {
    match node_kind {
        "client" => OAUTH_CLIENT,
        "webhook" => WEBHOOK_OUTBOUND,
        _ => APP,
    }
}

pub fn run(ctx: &mut Ctx, planned: &Planned, steps: &[Step], manifests: &BTreeMap<String, PathBuf>) -> Result<(), Fail> {
    let mut state = State { recorded: planned.kept.clone(), ..State::default() };
    for (done, step) in steps.iter().enumerate() {
        if let Err(fail) = state.step(ctx, step, manifests) {
            return Err(state.partial(ctx, planned, (done, steps.len()), fail));
        }
    }
    activate(ctx, planned, &state.recorded, "complete")?;
    state.report(ctx, "complete");
    Ok(())
}

impl State {
    fn step(&mut self, ctx: &mut Ctx, step: &Step, manifests: &BTreeMap<String, PathBuf>) -> Result<(), Fail> {
        match &step.records {
            Recording::ClientSecret(client) => {
                let id = self.minted.get(client).cloned().ok_or_else(|| Fail::failure(format!("{client} minted no secret")))?;
                self.secrets.iter_mut().filter(|s| s["handle"] == json!(client)).for_each(|s| s["handle"] = json!(step.handle));
                self.recorded.insert(step.handle.clone(), id);
            }
            _ if step.argv.is_empty() => {}
            records => {
                let argv = self.resolve(&step.argv, manifests)?;
                let answer = self.invoke(ctx, &step.handle, argv)?;
                self.record(ctx, step, records, &answer)?;
            }
        }
        let id = self.recorded.get(&step.handle).cloned().unwrap_or_else(|| "-".into());
        self.rows.push(vec![step.handle.clone(), step.kind.clone(), step.action.to_string(), id]);
        Ok(())
    }

    fn resolve(&self, argv: &[Part], manifests: &BTreeMap<String, PathBuf>) -> Result<Vec<OsString>, Fail> {
        argv.iter()
            .map(|p| match p {
                Part::Text(t) | Part::Id(t) => Ok(OsString::from(t)),
                Part::Made(h) => self.made.get(h).map(OsString::from).ok_or_else(|| Fail::failure(format!("{h} was not made"))),
                Part::Manifest(h) => manifests.get(h).map(|p| p.clone().into_os_string()).ok_or_else(|| Fail::failure(format!("no manifest for {h}"))),
            })
            .collect()
    }

    /// One inner verb; its JSON, any secret it revealed kept even when it
    /// failed part-way.
    fn invoke(&mut self, ctx: &mut Ctx, handle: &str, mut argv: Vec<OsString>) -> Result<Value, Fail> {
        argv.extend(["--json".into(), "--profile".into(), OsString::from(&ctx.profile)]);
        let outcome = ctx.app.run(&argv, Box::new(std::io::sink()));
        let answer: Value = serde_json::from_slice(&outcome.stdout).unwrap_or(Value::Null);
        let secret = if answer["secret"].is_object() {
            Some(json!({ "handle": handle, "id": answer["secret"]["id"], "value": answer["secret"]["value"] }))
        } else {
            answer["secret_id"].as_str().map(|id| json!({ "handle": handle, "id": id, "value": answer["secret"] }))
        };
        if let Some(s) = secret {
            self.minted.insert(handle.to_string(), s["id"].as_str().unwrap_or_default().to_string());
            self.secrets.push(s);
        }
        match outcome.fail {
            Some(fail) => Err(fail),
            None => Ok(answer),
        }
    }

    fn record(&mut self, ctx: &mut Ctx, step: &Step, records: &Recording, answer: &Value) -> Result<(), Fail> {
        match records {
            Recording::RuntimeId => {
                let platform_id = answer["id"].as_str().unwrap_or_default().to_string();
                self.made.insert(step.handle.clone(), platform_id.clone());
                let runtime_id = runtime_id(ctx, &kind_of(&step.kind), &platform_id, answer)?;
                self.recorded.insert(step.handle.clone(), runtime_id);
            }
            Recording::SecretId => {
                self.recorded.insert(step.handle.clone(), answer["secret_id"].as_str().unwrap_or_default().to_string());
            }
            Recording::ClientSecret(_) | Recording::Unchanged => {}
        }
        Ok(())
    }

    fn partial(self, ctx: &mut Ctx, planned: &Planned, (done, total): (usize, usize), mut fail: Fail) -> Fail {
        let activated = activate(ctx, planned, &self.recorded, "partial");
        self.report(ctx, "partial");
        if let Err(not_saved) = activated {
            fail.hints.push(format!("the partial record was not saved: {} ({})", not_saved.message, not_saved.code));
        }
        let (done, total) = (done.to_string(), total.to_string());
        fail.hints.push(message("canvas_apply_partial", &[("done", &done), ("total", &total), ("id", &planned.canvas_id)]));
        fail
    }

    /// D7 step 7: `HANDLE  KIND  ACTION  ID`, then every new secret, once.
    fn report(&self, ctx: &mut Ctx, status: &str) {
        if ctx.out.json {
            let objects: serde_json::Map<String, Value> = self.recorded.iter().map(|(h, id)| (h.clone(), json!({ "id": id }))).collect();
            ctx.out.json_value(&json!({ "objects": objects, "secrets": self.secrets, "status": status }));
        } else {
            let mut text = render(&["HANDLE", "KIND", "ACTION", "ID"], &self.rows);
            if !self.secrets.is_empty() {
                let rows: Vec<Vec<String>> = self.secrets.iter().map(|s| ["handle", "id", "value"].iter().map(|k| s[*k].as_str().unwrap_or("-").to_string()).collect()).collect();
                text.push('\n');
                text.push_str(&render(&["HANDLE", "SECRET_ID", "SECRET"], &rows));
            }
            ctx.out.print(&text);
        }
        if !self.secrets.is_empty() {
            ctx.out.note("Each secret is shown once: store them now. They cannot be read again.");
        }
    }
}

/// The object's `runtime_id`, set once publishing has projected
/// (16.10.26v §5). Still `null` after ten reads: apply stops, naming it.
fn runtime_id(ctx: &mut Ctx, k: &Kind, platform_id: &str, answer: &Value) -> Result<String, Fail> {
    let mut current = answer.clone();
    for _ in 0..PUBLISH_POLLS {
        if let Some(id) = current["runtime_id"].as_str() {
            return Ok(id.to_string());
        }
        ctx.app.http.clock.sleep(1);
        current = objects::get(ctx, k, platform_id)?;
    }
    current["runtime_id"].as_str().map(str::to_string).ok_or_else(|| {
        Fail::new(Exit::Unavailable, "object_projection_pending", format!("{} {platform_id} is still publishing; nothing after it was applied", k.word))
    })
}

/// `{draft_version, outcome, objects}`: runtime ids only (16.10.26x D4).
fn activate(ctx: &mut Ctx, planned: &Planned, recorded: &Records, outcome: &str) -> Result<Value, Fail> {
    let objects: serde_json::Map<String, Value> = recorded.iter().map(|(h, id)| (h.clone(), json!({ "id": id }))).collect();
    let body = Body::Json(json!({ "draft_version": planned.draft_version, "outcome": outcome, "objects": objects }));
    let route = fixed(Fixed::CanvasActivate);
    ctx.call(&Request { route: &route, vars: &[("id", &planned.canvas_id)], query: &[], body }).map_err(|f| {
        if f.code != "canvas_conflict" {
            return f;
        }
        f.hint(format!("the canvas changed while applying; run `lingara canvas get {}`", planned.canvas_id))
    })
}
