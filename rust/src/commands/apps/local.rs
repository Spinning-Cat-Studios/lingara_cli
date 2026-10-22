//! The local verbs (ADR 16.10.26ae D4, D9): `apps status` reads the dev
//! version; `apps pin`, `apps unpin` and `apps list --pinned` edit or read
//! `platform.toml` alone (pin's one read is `app.get` for a name).

use std::path::{Path, PathBuf};

use serde_json::{Value, json};

use super::{dev_api, rows};
use crate::app::Ctx;
use crate::cli::apps::{ConfigArg, PinArgs, StatusArgs, UnpinArgs};
use crate::commands::objects::{self, APP};
use crate::exit::{Exit, Fail};
use crate::output::table;
use crate::platform_toml::write::{self, NewRow};
use crate::platform_toml::{self, DEFAULT_PORT, app_name_ok};

/// `apps status`: live with its lease, parked with its last URL, or the
/// serving version; `--json` is the `GET …/dev` body as the server answered.
pub fn status(ctx: &mut Ctx, args: &StatusArgs) -> Result<(), Fail> {
    let (toml, cwd) = rows::open(ctx, &args.config)?;
    let row = rows::resolve(&toml, args.name.as_deref(), &cwd)?;
    let id = row.id.clone().ok_or_else(|| Fail::usage(format!("{} is not registered yet: run lingara apps update {}", row.name, row.name)))?;
    let view = dev_api::get(ctx, &id)?;
    if ctx.out.json {
        ctx.out.json_value(&view);
        return Ok(());
    }
    let line = status_line(&view);
    ctx.out.print(&format!("{line}\n"));
    Ok(())
}

/// D9's three lines. The lease time is `HH:MM` of the server's UTC stamp.
pub fn status_line(view: &Value) -> String {
    let Some(url) = view["render_url"].as_str() else { return "dev: serving version".into() };
    if view["session"]["state"] == "live" {
        let lease = view["session"]["lease_expires_at"].as_str().and_then(|t| t.get(11..16)).unwrap_or("?");
        return format!("dev: live {url} (lease until {lease} UTC)");
    }
    format!("dev: parked (last {url})")
}

/// An id is 8-4-4-4-12 hex; anything else names a local row.
fn uuid_shaped(s: &str) -> bool {
    let groups: Vec<&str> = s.split('-').collect();
    let lens = [8, 4, 4, 4, 12];
    groups.len() == 5 && groups.iter().zip(lens).all(|(g, n)| g.len() == n && g.chars().all(|c| c.is_ascii_hexdigit()))
}

/// A platform object's name as a local handle: `Luba Dive Log` → `luba-dive-log`.
fn handle_of(name: &str) -> String {
    let lowered: String = name.chars().map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' }).collect();
    lowered.split('-').filter(|w| !w.is_empty()).collect::<Vec<_>>().join("-")
}

/// The file pin writes: the one `open` would find, else a new one here.
fn pin_target(ctx: &mut Ctx, config: &ConfigArg, cwd: &Path) -> Result<(PathBuf, Vec<String>), Fail> {
    match rows::locate(ctx, config, cwd).filter(|p| p.is_file()) {
        Some(path) => {
            let toml = rows::load_at(ctx, &path, cwd)?;
            Ok((path, toml.apps.into_iter().map(|r| r.name).collect()))
        }
        None => Ok((rows::locate(ctx, config, cwd).unwrap_or_else(|| cwd.join(platform_toml::FILE)), Vec::new())),
    }
}

/// `apps pin <id|name> [--path <dir>]`: a row in platform.toml and, when it
/// has none of that name, in the example. Never a platform write.
pub fn pin(ctx: &mut Ctx, args: &PinArgs) -> Result<(), Fail> {
    let cwd = rows::cwd()?;
    let (name, id) = if uuid_shaped(&args.app) {
        let object = objects::get(ctx, &APP, &args.app)?;
        (handle_of(object["name"].as_str().unwrap_or_default()), Some(args.app.clone()))
    } else {
        if args.path.is_none() {
            return Err(Fail::usage(format!("pinning {} by name needs --path <dir>", args.app)));
        }
        (args.app.clone(), None)
    };
    if !app_name_ok(&name) {
        return Err(Fail::message(Exit::Usage, "app_name_invalid", &[("name", &name)]));
    }
    let (file, names) = pin_target(ctx, &args.config, &cwd)?;
    if names.contains(&name) {
        return Err(Fail::usage(format!("{} already pins {name}", file.strip_prefix(&cwd).unwrap_or(&file).display())));
    }
    let base = file.parent().map_or_else(|| cwd.clone(), Path::to_path_buf);
    let project = cwd.join(args.path.clone().unwrap_or_else(|| PathBuf::from(".")));
    let project = project.canonicalize().unwrap_or(project);
    let path = match project.strip_prefix(&base) {
        Ok(rel) if rel.as_os_str().is_empty() => ".".to_string(),
        Ok(rel) => rel.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect::<Vec<_>>().join("/"),
        Err(_) => project.display().to_string(),
    };
    let row = NewRow { name: &name, path: &path, port: DEFAULT_PORT, start: None, manifest: None };
    write::pin_row(&file, &row, id.as_deref())?;
    ctx.out.nothing();
    Ok(())
}

/// `apps unpin <name>`: platform.toml only; the shared example changes by review.
pub fn unpin(ctx: &mut Ctx, args: &UnpinArgs) -> Result<(), Fail> {
    let (toml, cwd) = rows::open(ctx, &args.config)?;
    if !write::remove_row(&toml.file, &args.name)? {
        return Err(Fail::message(Exit::Usage, "app_row_unknown", &[("file", &toml.shown(&cwd)), ("value", &args.name)]));
    }
    ctx.out.nothing();
    Ok(())
}

/// `apps list --pinned`: the rows, as JSON or as a NAME/ID/PATH/PORT table.
pub fn list_pinned(ctx: &mut Ctx, config: &ConfigArg) -> Result<(), Fail> {
    let (toml, _) = rows::open(ctx, config)?;
    let rows: Vec<Value> = toml.apps.iter().map(|r| json!({ "name": r.name, "id": r.id, "path": r.path, "port": r.port })).collect();
    if ctx.out.json {
        ctx.out.json_value(&Value::Array(rows));
        return Ok(());
    }
    let cells: Vec<Vec<String>> = rows.iter().map(|r| ["name", "id", "path", "port"].iter().map(|k| table::cell(&r[*k])).collect()).collect();
    ctx.out.print(&table::render(&["NAME", "ID", "PATH", "PORT"], &cells));
    Ok(())
}
