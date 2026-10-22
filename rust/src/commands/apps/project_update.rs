//! `lingara apps update [name]` without `--manifest` (ADR 16.10.26ae D6):
//! the project's manifest goes to the **dev version** (`PATCH …/dev`, never
//! its `render_url`), or with `--version` becomes an immutable draft. A row
//! with no id is registered first.

use serde_json::{Value, json};

use super::manifest::ephemeral;
use super::{dev_api, project, rows};
use crate::app::Ctx;
use crate::cli::apps::UpdateArgs;
use crate::exit::{Exit, Fail};
use crate::http::routes::{Op, object};
use crate::http::{Body, Request};

pub fn run(ctx: &mut Ctx, args: &UpdateArgs) -> Result<(), Fail> {
    let (toml, cwd) = rows::open(ctx, &args.config)?;
    let row = rows::resolve(&toml, args.id.as_deref(), &cwd)?.clone();
    let manifest = project::read(&toml, &row, &cwd)?;
    if args.version.is_some() && ephemeral(manifest["render_url"].as_str().unwrap_or_default()) {
        let file = project::source(&toml, &row, &cwd);
        return Err(Fail::message(Exit::RenderUrlEphemeral, "render_url_ephemeral", &[("file", &file)]));
    }
    let warnings = project::warn(ctx, &manifest);
    let Some(id) = row.id.clone() else {
        return project::register(ctx, &toml, &row, manifest).map(|_| ());
    };
    let answer = match &args.version {
        Some(semver) => cut(ctx, &id, semver, manifest)?,
        None => dev_api::patch_with_retry(ctx, &id, |draft_version| json!({ "manifest": manifest, "draft_version": draft_version }))?,
    };
    print(ctx, answer, &warnings);
    Ok(())
}

/// Step 3: an immutable draft from the project's manifest. The server's
/// lifecycle rules stand (a semver not greater than the last, a second
/// editable version), as `refused`.
fn cut(ctx: &mut Ctx, id: &str, semver: &str, manifest: Value) -> Result<Value, Fail> {
    let route = object("app", Op::VersionCreate);
    let body = json!({ "semver": semver, "body": { "manifest": manifest } });
    ctx.call(&Request { route: &route, vars: &[("id", id)], query: &[], body: Body::Json(body) })
}

/// The server's answer, with `warnings: [code]` under `--json`.
fn print(ctx: &mut Ctx, mut answer: Value, warnings: &[&str]) {
    if ctx.out.json && answer.is_object() {
        answer["warnings"] = json!(warnings);
    }
    ctx.out.value(&answer);
}
