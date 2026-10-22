//! `platform.toml` (ADR 16.10.26ae D4): the gitignored, per-developer file
//! naming the apps a checkout works on. Found by `--config`, then
//! `LINGARA_PLATFORM_TOML`, then by walking up from the current directory as
//! Cargo does. Read in a fixed order, so the first refusal is the same in
//! every kit: TOML syntax (`platform_toml_schema`), a secret-shaped value or
//! key (`platform_toml_secret`, `secrets`), then the rules
//! `spec/platform-toml.schema.json` writes (`platform_toml_schema`). A
//! tracked file is only a warning (`git`).

pub mod git;
mod schema;
mod secrets;
pub mod write;

pub use schema::app_name_ok;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use toml::Value;

use crate::app::Env;
use crate::exit::{Exit, Fail};

pub const FILE: &str = "platform.toml";
pub const EXAMPLE: &str = "platform.toml.example";
/// The example's id, which every kit reads as no id (spec/templates.md).
pub const PLACEHOLDER_ID: &str = "00000000-0000-0000-0000-000000000000";
pub const DEFAULT_PORT: u16 = 8787;

/// One `[[apps]]` row.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Row {
    pub name: String,
    pub id: Option<String>,
    pub path: String,
    pub port: u16,
    pub start: Option<String>,
    pub render_path: Option<String>,
    pub manifest: Option<String>,
    pub manifest_command: Option<String>,
    pub credentials: Vec<String>,
    pub env_file: Option<String>,
    pub env: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Default)]
pub struct PlatformToml {
    pub file: PathBuf,
    pub api: Option<String>,
    pub profile: Option<String>,
    pub apps: Vec<Row>,
}

impl PlatformToml {
    /// The directory the file is in, which every row's `path` is relative to.
    pub fn dir(&self) -> PathBuf {
        self.file.parent().map_or_else(|| PathBuf::from("."), Path::to_path_buf)
    }

    pub fn project_dir(&self, row: &Row) -> PathBuf {
        self.dir().join(&row.path)
    }

    /// The file's name as messages print it: relative to `cwd` where it can be.
    pub fn shown(&self, cwd: &Path) -> String {
        self.file.strip_prefix(cwd).unwrap_or(&self.file).display().to_string()
    }
}

/// `--config`, then `LINGARA_PLATFORM_TOML`, then the nearest `platform.toml`
/// at or above `cwd`.
pub fn locate(config: Option<&Path>, env: &Env, cwd: &Path) -> Option<PathBuf> {
    let explicit = config.map(Path::to_path_buf).or_else(|| env.get("LINGARA_PLATFORM_TOML").map(PathBuf::from));
    match explicit {
        Some(path) => Some(if path.is_absolute() { path } else { cwd.join(path) }),
        None => find_upward(cwd),
    }
}

/// The nearest `platform.toml` in `from` or a directory above it.
pub fn find_upward(from: &Path) -> Option<PathBuf> {
    from.ancestors().map(|d| d.join(FILE)).find(|p| p.is_file())
}

pub fn schema_fail(file: &str, reason: &str) -> Fail {
    Fail::message(Exit::PlatformTomlSchema, "platform_toml_schema", &[("file", file), ("reason", reason)])
}

/// Reads and checks `path`; `shown` is how messages name it.
pub fn load(path: &Path, shown: &str) -> Result<PlatformToml, Fail> {
    let text = std::fs::read_to_string(path).map_err(|e| schema_fail(shown, &e.to_string()))?;
    let value: Value = toml::from_str(&text).map_err(|e| schema_fail(shown, e.message()))?;
    if let Some(key) = secrets::first_secret(&value) {
        return Err(Fail::message(Exit::PlatformTomlSecret, "platform_toml_secret", &[("file", shown), ("key", &key)]));
    }
    schema::check(&value).map_err(|reason| schema_fail(shown, &reason))?;
    Ok(read(path, &value))
}

fn text(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string)
}

fn read(path: &Path, value: &Value) -> PlatformToml {
    let platform = value.get("platform");
    let apps = value.get("apps").and_then(Value::as_array).map(|a| a.iter().map(row).collect()).unwrap_or_default();
    PlatformToml {
        file: path.to_path_buf(),
        api: platform.and_then(|p| text(p, "api")),
        profile: platform.and_then(|p| text(p, "profile")),
        apps,
    }
}

fn row(v: &Value) -> Row {
    let strings = |key: &str| v.get(key).and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect()).unwrap_or_default();
    let env = v.get("env").and_then(Value::as_table).map(|t| t.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect());
    Row {
        name: text(v, "name").unwrap_or_default(),
        id: text(v, "id").filter(|id| id != PLACEHOLDER_ID),
        path: text(v, "path").unwrap_or_else(|| ".".into()),
        port: v.get("port").and_then(Value::as_integer).and_then(|p| u16::try_from(p).ok()).unwrap_or(DEFAULT_PORT),
        start: text(v, "start"),
        render_path: text(v, "render_path"),
        manifest: text(v, "manifest"),
        manifest_command: text(v, "manifest_command"),
        credentials: strings("credentials"),
        env_file: text(v, "env_file"),
        env: env.unwrap_or_default(),
    }
}

#[cfg(test)]
#[path = "platform_toml_tests.rs"]
mod platform_toml_tests;
