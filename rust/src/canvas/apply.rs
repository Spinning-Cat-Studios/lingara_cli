//! `canvas apply <id>`, D7 steps 1–3, and the two flags that stop there:
//! `--dry-run` prints the verdicts, `--print-commands` the argvs. Neither
//! writes. Steps 4–7 are `execute`'s.

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde_json::{Value, json};

use super::argv::{self, Step, Stop};
use super::definition::Definition;
use super::execute;
use super::plan::{self, Live, PlanOp, Records, Refusal, Row};
use crate::app::Ctx;
use crate::cli::canvas::ApplyArgs;
use crate::commands::canvas::get as canvas_get;
use crate::commands::objects::{self, APP, OAUTH_CLIENT, WEBHOOK_OUTBOUND};
use crate::commands::{Columns, print_rows};
use crate::exit::{Exit, Fail};
use crate::http::routes::{Fixed, fixed};
use crate::http::{Body, Request};

const VERDICT_COLUMNS: Columns = &[("HANDLE", "handle"), ("KIND", "kind"), ("OP", "op"), ("VERDICT", "verdict"), ("CODE", "code")];

/// What one apply works over: the draft, its record and the live lists.
pub struct Planned {
    pub canvas_id: String,
    pub draft_version: i64,
    pub kept: Records,
    pub live: Live,
    pub ops: Vec<PlanOp>,
}

fn refused(message: impl Into<String>) -> Fail {
    Fail::new(Exit::Refused, "canvas_apply_refused", message)
}

/// The active record overlaid by the draft's, kept to the draft's nodes
/// (`recordedObjects`).
fn recorded(canvas: &Value, def: &Definition) -> Records {
    let mut all = Records::new();
    for side in ["active", "draft"] {
        for (handle, v) in canvas[side]["objects"].as_object().into_iter().flatten() {
            all.insert(handle.clone(), v["id"].as_str().unwrap_or_default().to_string());
        }
    }
    all.retain(|h, _| def.nodes.iter().any(|n| &n.id == h));
    all
}

fn rows(items: Vec<Value>) -> Vec<Row> {
    items.iter().map(Row::read).collect()
}

/// D7 step 1: the canvas and the three lists, every page.
fn read(ctx: &mut Ctx, id: &str) -> Result<(Value, Live), Fail> {
    let canvas = canvas_get(ctx, id)?;
    if canvas["draft"].is_null() {
        return Err(refused("nothing to apply: save a draft with `lingara canvas draft`"));
    }
    let clients = rows(objects::list(ctx, &OAUTH_CLIENT)?);
    let webhooks = rows(objects::list(ctx, &WEBHOOK_OUTBOUND)?);
    let apps = rows(objects::list(ctx, &APP)?);
    Ok((canvas, Live { clients, webhooks, apps }))
}

fn refusal_fail(r: Refusal) -> Fail {
    let text = match r {
        Refusal::Ephemeral(n) => {
            let file = format!("node {n}'s manifest");
            return Fail::message(Exit::RenderUrlEphemeral, "render_url_ephemeral", &[("file", &file)]);
        }
        Refusal::Script(n) => format!("node {n} runs a script; apply this canvas in the browser"),
        Refusal::Fields(n, why) => format!("node {n}'s fields are not one the builder can apply: {why}"),
        Refusal::NoClient(n) => format!("node {n} needs exactly one client node upstream"),
        Refusal::Cycle => "the canvas's edges form a cycle".to_string(),
    };
    refused(text)
}

pub fn plan(ctx: &mut Ctx, id: &str) -> Result<Planned, Fail> {
    let (canvas, live) = read(ctx, id)?;
    let definition = Definition::read(&canvas["draft"]["definition"]);
    let kept = plan::still_live(&definition, &recorded(&canvas, &definition), &live);
    let ops = plan::build(&definition, &kept).map_err(refusal_fail)?;
    let draft_version = canvas["draft"]["draft_version"].as_i64().unwrap_or(0);
    Ok(Planned { canvas_id: id.to_string(), draft_version, kept, live, ops })
}

/// D7 step 3: zero writes, `canvas:read`.
fn dry_run(ctx: &mut Ctx, ops: &[PlanOp]) -> Result<Value, Fail> {
    let body = Body::Json(json!({ "ops": ops.iter().map(PlanOp::wire).collect::<Vec<_>>() }));
    ctx.call(&Request { route: &fixed(Fixed::CanvasDryRun), vars: &[], query: &[], body })
}

fn stop_fail(stop: Stop) -> Fail {
    match stop {
        Stop::Refused(all) => {
            let mut fail = refused(format!("the dry-run refused {} op(s); nothing was applied", all.len()));
            fail.hints = all.iter().map(|(h, code, error)| format!("{h}: {error} ({code})")).collect();
            fail
        }
        Stop::Replace(h) => refused(format!("node {h}: unexpected `would_replace` from the server; nothing was applied")),
    }
}

pub fn run(ctx: &mut Ctx, args: &ApplyArgs) -> Result<(), Fail> {
    let planned = plan(ctx, &args.id)?;
    let answer = dry_run(ctx, &planned.ops)?;
    let verdicts = answer["verdicts"].as_array().cloned().unwrap_or_default();
    if args.dry_run {
        print_verdicts(ctx, &answer, &verdicts);
        return if answer["ok"] == false { Err(refused("the dry-run refused at least one op")) } else { Ok(()) };
    }
    let steps = argv::steps(&planned.ops, &verdicts, &planned.live, &planned.kept).map_err(stop_fail)?;
    let manifests = write_manifests(&planned.ops)?;
    if args.print_commands {
        print_commands(ctx, &steps, &manifests);
        return Ok(());
    }
    let result = execute::run(ctx, &planned, &steps, &manifests);
    for path in manifests.values() {
        let _ = std::fs::remove_file(path);
    }
    result
}

fn print_verdicts(ctx: &mut Ctx, answer: &Value, verdicts: &[Value]) {
    if ctx.out.json {
        ctx.out.json_value(answer);
    } else {
        print_rows(ctx, verdicts, VERDICT_COLUMNS);
    }
}

/// Each app op's manifest, as the file its `--manifest` names.
fn write_manifests(ops: &[PlanOp]) -> Result<BTreeMap<String, PathBuf>, Fail> {
    let mut out = BTreeMap::new();
    for op in ops.iter().filter(|o| o.kind == "app") {
        let path = std::env::temp_dir().join(format!("lingara-{}-{}.manifest.json", std::process::id(), op.handle));
        let manifest = op.body.as_ref().map(|b| b["manifest"].clone()).unwrap_or(Value::Null);
        std::fs::write(&path, manifest.to_string()).map_err(|e| Fail::failure(format!("{}: {e}", path.display())))?;
        out.insert(op.handle.clone(), path);
    }
    Ok(out)
}

/// The matryoshka made visible: what apply would run, as shell lines.
fn print_commands(ctx: &mut Ctx, steps: &[Step], manifests: &BTreeMap<String, PathBuf>) {
    let manifest = |h: &str| manifests.get(h).map(|p| p.display().to_string()).unwrap_or_default();
    let lines: Vec<String> = steps.iter().filter(|s| !s.argv.is_empty()).map(|s| argv::shell_line(&s.argv, &manifest)).collect();
    if ctx.out.json {
        ctx.out.json_value(&json!(lines));
    } else {
        for line in lines {
            ctx.out.print(&format!("{line}\n"));
        }
    }
}
