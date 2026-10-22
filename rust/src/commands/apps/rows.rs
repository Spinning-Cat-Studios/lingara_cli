//! Opening `platform.toml` for a command and picking its row (ADR
//! 16.10.26ae D4, D7 step 1). Opening runs every refusal before any request,
//! warns once when git tracks the file, and applies its `[platform]` table:
//! `api` unless `LINGARA_API_URL` is set, `profile` unless `--profile` or
//! `LINGARA_PROFILE` chose one.

use std::path::{Path, PathBuf};

use crate::app::Ctx;
use crate::assets::message;
use crate::cli::apps::ConfigArg;
use crate::exit::{Exit, Fail};
use crate::platform_toml::{self, PlatformToml, Row, git};

/// The process's directory, through any symlink, so a row's path compares.
pub fn cwd() -> Result<PathBuf, Fail> {
    let dir = std::env::current_dir().map_err(|e| Fail::failure(format!("the current directory: {e}")))?;
    Ok(dir.canonicalize().unwrap_or(dir))
}

/// The file `--config`, `LINGARA_PLATFORM_TOML` or the walk up names, if any.
pub fn locate(ctx: &Ctx, config: &ConfigArg, cwd: &Path) -> Option<PathBuf> {
    platform_toml::locate(config.config.as_deref(), &ctx.app.env, cwd)
}

/// Reads and checks the file at `path`, warns if it is tracked, and applies
/// its `[platform]` table.
pub fn load_at(ctx: &mut Ctx, path: &Path, cwd: &Path) -> Result<PlatformToml, Fail> {
    let shown = path.strip_prefix(cwd).unwrap_or(path).display().to_string();
    let toml = platform_toml::load(path, &shown)?;
    if git::tracked(path) {
        ctx.out.note(&message("platform_toml_tracked", &[("path", &shown)]));
    }
    if ctx.app.env.get("LINGARA_API_URL").is_none()
        && let Some(api) = &toml.api
    {
        ctx.app.http.bases.api = api.clone();
    }
    if !ctx.profile_chosen
        && let Some(profile) = &toml.profile
    {
        ctx.profile = profile.clone();
    }
    Ok(toml)
}

/// The command's `platform.toml`, which must exist.
pub fn open(ctx: &mut Ctx, config: &ConfigArg) -> Result<(PlatformToml, PathBuf), Fail> {
    let cwd = cwd()?;
    let path = locate(ctx, config, &cwd).ok_or_else(|| {
        Fail::usage(format!("no {} at or above {}: run lingara apps create, or lingara apps pin", platform_toml::FILE, cwd.display()))
    })?;
    if !path.is_file() {
        return Err(platform_toml::schema_fail(&path.display().to_string(), "the file does not exist"));
    }
    Ok((load_at(ctx, &path, &cwd)?, cwd))
}

fn unknown(toml: &PlatformToml, cwd: &Path, value: &str) -> Fail {
    Fail::message(Exit::Usage, "app_row_unknown", &[("file", &toml.shown(cwd)), ("value", value)])
}

/// The row named (by `name` or `id`), or with none named: the only row, or
/// the one whose project contains `cwd` (the deepest, when they nest).
pub fn resolve<'a>(toml: &'a PlatformToml, wanted: Option<&str>, cwd: &Path) -> Result<&'a Row, Fail> {
    if let Some(value) = wanted {
        let found = toml.apps.iter().find(|r| r.name == value || r.id.as_deref() == Some(value));
        return found.ok_or_else(|| unknown(toml, cwd, value));
    }
    if let [only] = toml.apps.as_slice() {
        return Ok(only);
    }
    let depth = |r: &Row| {
        let dir = toml.project_dir(r);
        let dir = dir.canonicalize().unwrap_or(dir);
        cwd.starts_with(&dir).then(|| dir.components().count())
    };
    let mut containing: Vec<(usize, &Row)> = toml.apps.iter().filter_map(|r| depth(r).map(|d| (d, r))).collect();
    containing.sort_by_key(|(d, _)| std::cmp::Reverse(*d));
    match containing.as_slice() {
        [(d, row), rest @ ..] if rest.first().is_none_or(|(d2, _)| d2 < d) => Ok(row),
        _ if toml.apps.is_empty() => Err(unknown(toml, cwd, "(no app is pinned)")),
        _ => {
            let names: Vec<&str> = toml.apps.iter().map(|r| r.name.as_str()).collect();
            Err(Fail::message(Exit::Usage, "app_row_ambiguous", &[("file", &toml.shown(cwd)), ("names", &names.join(", "))]))
        }
    }
}
