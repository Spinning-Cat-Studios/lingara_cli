//! `lingara apps create <name>` without `--manifest` (ADR 16.10.26ae D1):
//! scaffold offline, pin the project in `platform.toml`, then register it
//! as a private app unless `--no-register` or there is no session.

use std::path::{Path, PathBuf};

use clap::ValueEnum;

use super::project;
use super::rows;
use super::template::{self, Keys};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::apps::{CreateArgs, Lang};
use crate::exit::{Exit, Fail};
use crate::platform_toml::write::{self, NewRow};
use crate::platform_toml::{self, DEFAULT_PORT, app_name_ok};

const DEFAULT_DESCRIPTION: &str = "A Lingara app.";
const DEFAULT_SLOT: &str = "home.side";

/// The description goes inside a string literal in every template language.
fn description_ok(text: &str) -> bool {
    !text.chars().any(|c| matches!(c, '"' | '\\' | '`' | '$' | '#' | '{' | '}') || c.is_control())
}

fn slice_ok(kind: &str) -> bool {
    kind.starts_with(|c: char| c.is_ascii_lowercase()) && kind.chars().all(|c| c.is_ascii_lowercase() || c == '_')
}

fn keys(args: &CreateArgs) -> Result<Keys, Fail> {
    if !app_name_ok(&args.name) {
        return Err(Fail::message(Exit::Usage, "app_name_invalid", &[("name", &args.name)]));
    }
    let description = args.description.clone().unwrap_or_else(|| DEFAULT_DESCRIPTION.into());
    if !description_ok(&description) {
        return Err(Fail::usage("--description may not hold \", \\, `, $, #, { or }, or a control character"));
    }
    if let Some(bad) = args.context.iter().find(|c| !slice_ok(c)) {
        return Err(Fail::usage(format!("--context {bad} is not a context slice name")));
    }
    let slots = if args.slot.is_empty() { vec![DEFAULT_SLOT.to_string()] } else { args.slot.clone() };
    let display_name = project::display_name(&args.name);
    Ok(Keys { name: args.name.clone(), display_name, description, port: args.port.unwrap_or(DEFAULT_PORT), slots, context: args.context.clone() })
}

fn lang_id(lang: Option<Lang>) -> String {
    let lang = lang.unwrap_or(Lang::Typescript);
    lang.to_possible_value().map(|v| v.get_name().to_string()).unwrap_or_else(|| "typescript".into())
}

fn shown(path: &Path, cwd: &Path) -> String {
    path.strip_prefix(cwd).unwrap_or(path).display().to_string()
}

/// `dir` relative to `base`, `/`-separated; `.` when they are one.
fn relative(dir: &Path, base: &Path) -> String {
    match dir.strip_prefix(base) {
        Ok(rel) if rel.as_os_str().is_empty() => ".".into(),
        Ok(rel) => rel.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect::<Vec<_>>().join("/"),
        Err(_) => dir.display().to_string(),
    }
}

/// Step 3's file: `--config` or `LINGARA_PLATFORM_TOML`, else the nearest
/// above the project's parent, else a new one inside the project.
fn pin_file(ctx: &mut Ctx, args: &CreateArgs, dir: &Path, cwd: &Path) -> Result<PathBuf, Fail> {
    let explicit = platform_toml::locate(args.config.config.as_deref(), &ctx.app.env, cwd).filter(|_| {
        args.config.config.is_some() || ctx.app.env.get("LINGARA_PLATFORM_TOML").is_some()
    });
    let found = explicit.or_else(|| dir.parent().and_then(platform_toml::find_upward));
    let Some(file) = found.filter(|f| f.is_file()) else { return Ok(dir.join(platform_toml::FILE)) };
    let toml = rows::load_at(ctx, &file, cwd)?;
    if toml.apps.iter().any(|r| r.name == args.name) {
        return Err(Fail::usage(format!("{} already pins {}", shown(&file, cwd), args.name)));
    }
    Ok(file)
}

pub fn run(ctx: &mut Ctx, args: &CreateArgs) -> Result<(), Fail> {
    let keys = keys(args)?;
    let cwd = rows::cwd()?;
    let dir = args.dir.clone().map_or_else(|| cwd.join(&args.name), |d| cwd.join(d));
    if std::fs::read_dir(&dir).is_ok_and(|mut entries| entries.next().is_some()) {
        return Err(Fail::message(Exit::Usage, "dir_not_empty", &[("path", &shown(&dir, &cwd))]));
    }
    let (lang, variant) = (lang_id(args.lang), if args.bare { "bare" } else { "default" });
    let (files, meta) = template::render(&lang, variant, &keys)?;
    let file = pin_file(ctx, args, &dir, &cwd)?;
    std::fs::create_dir_all(&dir).map_err(|e| Fail::failure(format!("{}: {e}", dir.display())))?;
    let dir = dir.canonicalize().unwrap_or(dir);
    template::write_all(&dir, &files)?;
    let base = file.parent().map_or_else(|| dir.clone(), Path::to_path_buf);
    let base = base.canonicalize().unwrap_or(base);
    let path = relative(&dir, &base);
    let manifest = meta.manifest.as_ref().map(|(k, v)| (k.as_str(), v.as_str()));
    let row = NewRow { name: &args.name, path: &path, port: keys.port, start: meta.start.as_deref(), manifest };
    write::pin_row(&base.join(file.file_name().unwrap_or_default()), &row, None)?;
    write::ensure_gitignore(&base)?;
    register(ctx, args, &base.join(file.file_name().unwrap_or_default()), &cwd)
}

/// Step 2, last: offline when `--no-register`, or with no session to use.
fn register(ctx: &mut Ctx, args: &CreateArgs, file: &Path, cwd: &Path) -> Result<(), Fail> {
    if args.no_register {
        ctx.out.nothing();
        return Ok(());
    }
    if ctx.ci_pair().is_none() && ctx.app.stores.load(&ctx.profile)?.is_none() {
        ctx.out.note(&message("apps_create_login_hint", &[("name", &args.name)]));
        ctx.out.nothing();
        return Ok(());
    }
    let toml = rows::load_at(ctx, file, cwd)?;
    let row = rows::resolve(&toml, Some(&args.name), cwd)?.clone();
    let manifest = project::read(&toml, &row, cwd)?;
    project::warn(ctx, &manifest);
    project::register(ctx, &toml, &row, manifest).map(|_| ())
}
