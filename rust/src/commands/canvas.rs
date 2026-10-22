//! `lingara canvas …` (D1, D2, D7). Content goes through `/v1/canvases`;
//! lifecycle (delete with the typed email) through `/platform/objects`,
//! after `canvas.lookup` turns the builder's id into the platform object's.
//! `apply` is `crate::canvas`'s.

use std::path::Path;

use serde_json::{Value, json};

use super::deletes::prompt;
use super::objects::{self, Kind};
use super::{Columns, print_rows};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::DeleteArgs;
use crate::cli::canvas::{CanvasCmd, CreateArgs, DraftArgs, UpdateArgs};
use crate::confirm::confirm_destructive;
use crate::exit::{Exit, Fail};
use crate::http::routes::{Fixed, Op, fixed, object_named};
use crate::http::{Body, Request};

pub const CANVAS: Kind = Kind { word: "canvas", kind: "canvas", label: "canvas" };
const COLUMNS: Columns = &[("ID", "id"), ("NAME", "name"), ("DRAFT", "has_draft"), ("ACTIVE", "active_revision"), ("UPDATED", "updated_at")];

pub fn run(ctx: &mut Ctx, cmd: CanvasCmd) -> Result<(), Fail> {
    match cmd {
        CanvasCmd::Create(args) => create(ctx, &args).map_err(builder_hint),
        CanvasCmd::List => list(ctx).map_err(builder_hint),
        CanvasCmd::Get(arg) => {
            let canvas = get(ctx, &arg.id);
            print(ctx, canvas)
        }
        CanvasCmd::Update(args) => update(ctx, &args),
        CanvasCmd::Draft(args) => draft(ctx, &args),
        CanvasCmd::Apply(args) => crate::canvas::apply::run(ctx, &args),
        CanvasCmd::Delete(args) => delete(ctx, &args),
    }
}

/// A `/v1/canvases` 404 while the builder flag is off (16.10.26x D3). Where
/// no id could be wrong, say what it probably means.
fn builder_hint(fail: Fail) -> Fail {
    if fail.exit == Exit::NotFound { fail.hint("the canvas builder may not be enabled for this account") } else { fail }
}

fn print(ctx: &mut Ctx, value: Result<Value, Fail>) -> Result<(), Fail> {
    ctx.out.value(&value?);
    Ok(())
}

pub fn get(ctx: &mut Ctx, id: &str) -> Result<Value, Fail> {
    ctx.call(&Request { route: &fixed(Fixed::CanvasGet), vars: &[("id", id)], query: &[], body: Body::None })
}

pub fn definition(path: &Path) -> Result<Value, Fail> {
    let text = std::fs::read_to_string(path).map_err(|e| Fail::usage(format!("{}: {e}", path.display())))?;
    serde_json::from_str(&text).map_err(|e| Fail::usage(format!("{} is not JSON: {e}", path.display())))
}

/// `createCanvas` answers the canvas itself, so there is no final read.
fn create(ctx: &mut Ctx, args: &CreateArgs) -> Result<(), Fail> {
    let def = match &args.file {
        Some(path) => definition(path)?,
        None => json!({ "schema": 2, "nodes": [], "edges": [] }),
    };
    let mut body = json!({ "definition": def });
    if let Some(name) = &args.name {
        body["name"] = json!(name);
    }
    let answer = ctx.call(&Request { route: &fixed(Fixed::CanvasCreate), vars: &[], query: &[], body: Body::Json(body) });
    print(ctx, answer)
}

fn list(ctx: &mut Ctx) -> Result<(), Fail> {
    let route = fixed(Fixed::CanvasList);
    let (mut items, mut cursor) = (Vec::new(), None::<String>);
    loop {
        let query: Vec<(&str, &str)> = cursor.as_deref().map(|c| ("cursor", c)).into_iter().collect();
        let page = ctx.call(&Request { route: &route, vars: &[], query: &query, body: Body::None })?;
        items.extend(page["items"].as_array().cloned().unwrap_or_default());
        match page["next_cursor"].as_str() {
            Some(next) => cursor = Some(next.to_string()),
            None => break,
        }
    }
    print_rows(ctx, &items, COLUMNS);
    Ok(())
}

fn update(ctx: &mut Ctx, args: &UpdateArgs) -> Result<(), Fail> {
    let body = Body::Json(json!({ "name": args.name }));
    let answer = ctx.call(&Request { route: &fixed(Fixed::CanvasRename), vars: &[("id", &args.id)], query: &[], body });
    print(ctx, answer)
}

/// Saves the definition at the draft's `draft_version` (`0`: a new draft).
/// It creates nothing else: that is `apply`'s alone (B7).
fn draft(ctx: &mut Ctx, args: &DraftArgs) -> Result<(), Fail> {
    let def = definition(&args.file)?;
    let canvas = get(ctx, &args.id)?;
    let draft_version = canvas["draft"]["draft_version"].as_i64().unwrap_or(0);
    let body = Body::Json(json!({ "definition": def, "draft_version": draft_version }));
    let answer = ctx.call(&Request { route: &fixed(Fixed::CanvasPutDraft), vars: &[("id", &args.id)], query: &[], body });
    print(ctx, answer)
}

/// The `canvas` object behind a `/v1/canvases` id (16.10.26v §8).
fn lookup(ctx: &mut Ctx, canvas_id: &str) -> Result<String, Fail> {
    let query = [("kind", "canvas"), ("runtime_id", canvas_id)];
    let page = ctx.call(&Request { route: &fixed(Fixed::CanvasLookup), vars: &[], query: &query, body: Body::None })?;
    let found = page["items"].as_array().and_then(|items| items.first()).and_then(|o| o["id"].as_str());
    found.map(str::to_string).ok_or_else(|| Fail::new(Exit::NotFound, "object_not_found", format!("no canvas {canvas_id}")))
}

fn delete(ctx: &mut Ctx, args: &DeleteArgs) -> Result<(), Fail> {
    let version = args.version.as_deref();
    let mut found: Option<String> = None;
    let email = confirm_destructive(ctx, &args.confirm, &mut |ctx| {
        let platform_id = lookup(ctx, &args.id)?;
        let route = object_named("canvas.object_get", Op::Get);
        let object = ctx.call(&Request { route: &route, vars: &[("id", &platform_id)], query: &[], body: Body::None })?;
        found = Some(platform_id);
        Ok(prompt(&CANVAS, &object, &args.id, version, |_| message("consequence_canvas", &[])))
    })?;
    let platform_id = match found {
        Some(id) => id,
        None => lookup(ctx, &args.id)?,
    };
    objects::delete(ctx, &CANVAS, &platform_id, version, &email)?;
    ctx.out.nothing();
    Ok(())
}
