//! The config dir and `config.toml` (D4). The dir is `LINGARA_CONFIG_DIR`,
//! else `$XDG_CONFIG_HOME/lingara`, else `~/.config/lingara` on macOS and
//! Linux alike (never `~/Library/Application Support`), and
//! `%APPDATA%\lingara` on Windows. `config.toml` holds only non-secret
//! per-profile settings: `[profiles.<name>]` with `api_url` and `output`.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::app::Env;

pub fn config_dir(env: &Env) -> Option<PathBuf> {
    if let Some(dir) = env.get("LINGARA_CONFIG_DIR") {
        return Some(PathBuf::from(dir));
    }
    if cfg!(windows) {
        return env.get("APPDATA").map(|d| Path::new(d).join("lingara"));
    }
    if let Some(xdg) = env.get("XDG_CONFIG_HOME") {
        return Some(Path::new(xdg).join("lingara"));
    }
    let home = env.get("HOME").map(PathBuf::from).or_else(dirs::home_dir)?;
    Some(home.join(".config").join("lingara"))
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct ProfileConfig {
    /// Third in D3's API resolution order.
    pub api_url: Option<String>,
    /// `json` makes `--json` the profile's default.
    pub output: Option<String>,
}

#[derive(Deserialize, Default)]
struct ConfigFile {
    #[serde(default)]
    profiles: BTreeMap<String, ProfileConfig>,
}

/// The profile's settings; a missing or unreadable file is no settings.
pub fn profile_config(dir: Option<&Path>, profile: &str) -> ProfileConfig {
    let Some(text) = dir.and_then(|d| std::fs::read_to_string(d.join("config.toml")).ok()) else {
        return ProfileConfig::default();
    };
    let file: ConfigFile = toml::from_str(&text).unwrap_or_default();
    file.profiles.get(profile).cloned().unwrap_or_default()
}
