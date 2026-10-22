//! `lingara apps dev [name]` (ADR 16.10.26ae D7), steps 1 and 2: resolve the
//! row and run every refusal offline, so a refused run sends no request;
//! then register a row with no id (D6 step 4) and hand over to `dev_run`.
//! `--reset` is here too: one `PATCH {render_url: null}` and nothing else.

use std::path::{Path, PathBuf};

use serde_json::{Value, json};

use super::{dev_api, dev_run, project, rows};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::apps::{DevArgs, Tunnel};
use crate::exit::{Exit, Fail};
use crate::platform_toml::{PlatformToml, Row, git};
use crate::tunnel::cloudflared;

/// Where the dev version will point: a cloudflared it spawns, or a URL.
pub enum Target {
    Cloudflared(PathBuf),
    Fixed(String),
}

/// Everything a session needs, decided before the first request.
pub struct Plan {
    pub name: String,
    pub id: String,
    pub dir: PathBuf,
    pub port: u16,
    pub start: String,
    pub render_path: String,
    pub target: Target,
    pub take_over: bool,
    pub client: bool,
    pub env: Vec<(String, String)>,
    pub write_env: Option<PathBuf>,
    pub show_env: bool,
    /// `LINGARA_TUNNEL_PROBE_URL`: where readiness is probed, never what is pointed at.
    pub probe: Option<String>,
}

pub fn run(ctx: &mut Ctx, args: &DevArgs) -> Result<(), Fail> {
    let (toml, cwd) = rows::open(ctx, &args.config)?;
    let row = rows::resolve(&toml, args.name.as_deref(), &cwd)?.clone();
    if args.reset {
        return reset(ctx, &row);
    }
    let mut plan = preflight(ctx, args, &toml, &row, &cwd)?;
    plan.id = match row.id.clone() {
        Some(id) => id,
        None => {
            let manifest = project::read(&toml, &row, &cwd)?;
            let app = project::register(ctx, &toml, &row, manifest)?;
            // The registration printed the app; `apps dev` prints one value, at its exit.
            ctx.out.stdout.clear();
            app["id"].as_str().unwrap_or_default().to_string()
        }
    };
    dev_run::session(ctx, &plan)
}

fn ignored_or_refuse(path: &Path, cwd: &Path) -> Result<(), Fail> {
    if git::ignored(path) {
        return Ok(());
    }
    let shown = path.strip_prefix(cwd).unwrap_or(path).display().to_string();
    Err(Fail::message(Exit::Usage, "path_not_ignored", &[("path", &shown)]))
}

fn target(ctx: &Ctx, args: &DevArgs) -> Result<Target, Fail> {
    match (args.tunnel, &args.render_url) {
        (Some(Tunnel::None), Some(url)) => Ok(Target::Fixed(url.clone())),
        (Some(Tunnel::None), None) => Err(Fail::usage("--tunnel none needs --render-url")),
        (_, Some(_)) => Err(Fail::usage("--render-url needs --tunnel none")),
        _ => cloudflared::find(&ctx.app.env).map(Target::Cloudflared),
    }
}

/// `KEY=VALUE` lines; blank lines, `#` comments and an `export ` prefix are skipped.
fn env_file(path: &Path) -> Result<Vec<(String, String)>, Fail> {
    let text = std::fs::read_to_string(path).map_err(|e| Fail::usage(format!("{}: {e}", path.display())))?;
    let pair = |line: &str| {
        let line = line.trim();
        let line = line.strip_prefix("export ").unwrap_or(line);
        let (k, v) = line.split_once('=')?;
        let v = v.trim();
        let unquoted = v.strip_prefix('"').and_then(|s| s.strip_suffix('"')).or_else(|| v.strip_prefix('\'').and_then(|s| s.strip_suffix('\'')));
        (!line.starts_with('#') && !k.trim().is_empty()).then(|| (k.trim().to_string(), unquoted.unwrap_or(v).to_string()))
    };
    Ok(text.lines().filter_map(pair).collect())
}

/// Step 2: every refusal before the network. The session record is read,
/// never refreshed; a refresh, if due, rides the first request.
fn preflight(ctx: &mut Ctx, args: &DevArgs, toml: &PlatformToml, row: &Row, cwd: &Path) -> Result<Plan, Fail> {
    let start = row.start.clone().ok_or_else(|| Fail::usage(format!("{}'s row has no start command", row.name)))?;
    let dir = toml.project_dir(row);
    let mut env: Vec<(String, String)> = row.env.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
    if let Some(file) = &row.env_file {
        let path = dir.join(file);
        ignored_or_refuse(&path, cwd)?;
        env.extend(env_file(&path)?);
    }
    let write_env = args.write_env.as_ref().map(|p| cwd.join(p));
    if let Some(path) = &write_env {
        ignored_or_refuse(path, cwd)?;
    }
    let target = target(ctx, args)?;
    if ctx.ci_pair().is_none() && ctx.app.stores.load(&ctx.profile)?.is_none() {
        return Err(Fail::auth("not signed in: run `lingara login`"));
    }
    Ok(Plan {
        name: row.name.clone(),
        id: String::new(),
        dir,
        port: args.port.unwrap_or(row.port),
        start,
        render_path: row.render_path.clone().unwrap_or_else(|| "/".into()),
        target,
        take_over: args.take_over,
        client: row.credentials.iter().any(|c| c == "client_credentials"),
        env,
        write_env,
        show_env: args.show_env,
        probe: ctx.app.env.get("LINGARA_TUNNEL_PROBE_URL").map(str::to_string),
    })
}

/// `--reset`: the owner's install back on the serving version. A live
/// session refuses it (`apps_dev_reset_held`).
fn reset(ctx: &mut Ctx, row: &Row) -> Result<(), Fail> {
    let id = row.id.clone().ok_or_else(|| Fail::usage(format!("{} is not registered yet: run lingara apps update {} first", row.name, row.name)))?;
    let answer = dev_api::patch_with_retry(ctx, &id, |draft_version| json!({ "render_url": Value::Null, "draft_version": draft_version }));
    match answer {
        Ok(_) => {
            ctx.out.note(&message("apps_dev_reset", &[("name", &row.name)]));
            ctx.out.nothing();
            Ok(())
        }
        Err(f) if f.exit == Exit::DevSessionHeld => Err(Fail::message(Exit::DevSessionHeld, "apps_dev_reset_held", &[("name", &row.name)])),
        Err(f) => Err(f),
    }
}
