//! `lingara apps …` (16.10.26ad D1, D2; 16.10.26ae). `create` and `update`
//! each have **one dispatch point** on whether `--manifest` is present: with
//! it, 16.10.26ad's registration path, untouched; without it, 16.10.26ae's
//! scaffolding (`scaffold`) and project-reading (`project_update`) paths.

mod dev;
mod dev_api;
mod dev_hold;
mod dev_run;
mod local;
pub mod manifest;
mod project;
mod project_update;
mod rows;
mod scaffold;
mod template;

use std::path::Path;

use serde_json::{Value, json};

use super::creates::Creation;
use super::deletes::{delete_object, parent};
use super::objects::{self, APP};
use super::{Columns, print_list, print_rows};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::apps::{AppsCmd, CreateArgs, SubmitArgs, UpdateArgs, VersionsCmd};
use crate::exit::{Exit, Fail};
use crate::http::routes::{Op, object};
use crate::http::{Body, Request};

pub const COLUMNS: Columns = &[("ID", "id"), ("NAME", "name"), ("CLIENT", "parent_id"), ("SERVING", "serving_version"), ("VISIBILITY", "visibility")];
const VERSION_COLUMNS: Columns = &[("SEMVER", "semver"), ("STATE", "state"), ("STATE_AT", "state_at"), ("REVIEWED", "review_basis")];

pub fn run(ctx: &mut Ctx, cmd: AppsCmd) -> Result<(), Fail> {
    match cmd {
        AppsCmd::Create(args) => create_either(ctx, &args),
        AppsCmd::List(args) if args.pinned => local::list_pinned(ctx, &args.config),
        AppsCmd::List(_) => print_list(ctx, &APP, COLUMNS),
        AppsCmd::Get(arg) => objects::print_get(ctx, &APP, &arg.id),
        AppsCmd::Update(args) => match &args.manifest {
            Some(_) => update(ctx, &args),
            None => project_update::run(ctx, &args),
        },
        AppsCmd::Dev(args) => dev::run(ctx, &args),
        AppsCmd::Status(args) => local::status(ctx, &args),
        AppsCmd::Pin(args) => local::pin(ctx, &args),
        AppsCmd::Unpin(args) => local::unpin(ctx, &args),
        AppsCmd::Delete(args) => delete_object(ctx, &APP, &args, |o| message("consequence_app", &[("client", &parent(o))])),
        AppsCmd::Submit(args) => submit(ctx, &args),
        AppsCmd::Versions(cmd) => versions(ctx, cmd),
    }
}

/// The one rule `conflicts` cannot say: `--manifest` and `--client` come
/// together (the registration form) or not at all (the scaffold).
fn create_either(ctx: &mut Ctx, args: &CreateArgs) -> Result<(), Fail> {
    match (&args.manifest, &args.client) {
        (Some(_), Some(_)) => create(ctx, args).map(|_| ()),
        (None, None) => scaffold::run(ctx, args),
        _ => Err(Fail::usage("--manifest and --client go together: both register an app from a file, neither scaffolds a project")),
    }
}

/// `--manifest <path>` is the file's JSON as the app body's `manifest`.
pub fn manifest_body(path: &Path) -> Result<Value, Fail> {
    let text = std::fs::read_to_string(path).map_err(|e| Fail::usage(format!("{}: {e}", path.display())))?;
    let manifest: Value = serde_json::from_str(&text).map_err(|e| Fail::usage(format!("{} is not JSON: {e}", path.display())))?;
    Ok(json!({ "manifest": manifest }))
}

/// The registration form: register the object, write no files.
pub fn create(ctx: &mut Ctx, args: &CreateArgs) -> Result<Value, Fail> {
    let (Some(path), Some(client)) = (&args.manifest, &args.client) else { return Err(Fail::usage("--manifest and --client go together")) };
    let body = manifest_body(path)?;
    let mut made = Creation::default();
    let result = made.object(ctx, &APP, &args.name, Some(client), body).map(|a| (APP, a["id"].as_str().unwrap_or_default().to_string()));
    made.conclude(ctx, result)
}

/// `apps update <id> --manifest <path>`: a new version from the file. A
/// quick-tunnel or `dev.invalid` render URL is refused before any request
/// (16.10.26ad D2 with 16.10.26ae D6 step 3's guard).
pub fn update(ctx: &mut Ctx, args: &UpdateArgs) -> Result<(), Fail> {
    let (Some(id), Some(path)) = (&args.id, &args.manifest) else { return Err(Fail::usage("apps update --manifest needs the app's id")) };
    let manifest = manifest_body(path)?["manifest"].clone();
    if manifest::ephemeral(manifest["render_url"].as_str().unwrap_or_default()) {
        return Err(Fail::message(Exit::RenderUrlEphemeral, "render_url_ephemeral", &[("file", &path.display().to_string())]));
    }
    let answer = objects::body_update(ctx, &APP, id, &|body| body["manifest"] = manifest.clone())?;
    ctx.out.value(&answer);
    Ok(())
}

/// `{"visibility": "public"}`: review itself is the operator's, in the portal.
fn submit(ctx: &mut Ctx, args: &SubmitArgs) -> Result<(), Fail> {
    let route = object(APP.word, Op::Submit);
    let vars = [("id", args.id.as_str()), ("semver", args.version.as_str())];
    let answer = ctx.call(&Request { route: &route, vars: &vars, query: &[], body: Body::Json(json!({ "visibility": "public" })) })?;
    ctx.out.value(&answer);
    Ok(())
}

fn versions(ctx: &mut Ctx, cmd: VersionsCmd) -> Result<(), Fail> {
    let answer = match cmd {
        VersionsCmd::List(arg) => return list_versions(ctx, &arg.id),
        VersionsCmd::Publish(a) => objects::version_op(ctx, &APP, Op::Publish, &a.id, &a.semver)?,
        VersionsCmd::Withdraw(a) => objects::version_op(ctx, &APP, Op::Withdraw, &a.id, &a.semver)?,
    };
    ctx.out.value(&answer);
    Ok(())
}

/// Every page, newest first; `next_cursor` is the last semver seen.
fn list_versions(ctx: &mut Ctx, id: &str) -> Result<(), Fail> {
    let route = object(APP.word, Op::Versions);
    let (mut items, mut cursor) = (Vec::new(), None::<String>);
    loop {
        let query: Vec<(&str, &str)> = cursor.as_deref().map(|c| ("cursor", c)).into_iter().collect();
        let page = ctx.call(&Request { route: &route, vars: &[("id", id)], query: &query, body: Body::None })?;
        items.extend(page["items"].as_array().cloned().unwrap_or_default());
        match page["next_cursor"].as_str() {
            Some(next) => cursor = Some(next.to_string()),
            None => break,
        }
    }
    print_rows(ctx, &items, VERSION_COLUMNS);
    Ok(())
}
