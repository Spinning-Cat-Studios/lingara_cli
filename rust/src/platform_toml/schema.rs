//! `spec/platform-toml.schema.json`'s rules over the parsed file, written
//! out: the kit carries no JSON Schema engine, and these are the same rules
//! in the same order, so a file one kit refuses every kit refuses.

use toml::Value;

const ROOT_KEYS: [&str; 3] = ["schema", "platform", "apps"];
const PLATFORM_KEYS: [&str; 2] = ["api", "profile"];
const ROW_KEYS: [&str; 11] =
    ["name", "id", "path", "port", "start", "render_path", "manifest", "manifest_command", "credentials", "env_file", "env"];
const CREDENTIALS: [&str; 2] = ["app_secret", "client_credentials"];

type Checked = Result<(), String>;

fn closed(table: &toml::Table, allowed: &[&str], at: &str) -> Checked {
    match table.keys().find(|k| !allowed.contains(&k.as_str())) {
        Some(k) => Err(format!("{at}{k} is not a key this file takes")),
        None => Ok(()),
    }
}

/// A valid app name: a lowercase letter, then up to 39 lowercase letters,
/// digits or hyphens.
pub fn app_name_ok(name: &str) -> bool {
    let mut chars = name.chars();
    chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && name.len() <= 40
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

pub fn check(value: &Value) -> Checked {
    let root = value.as_table().ok_or("the file is not a table")?;
    match root.get("schema") {
        Some(Value::Integer(1)) => {}
        Some(other) => return Err(format!("schema is {other}; this lingara reads 1")),
        None => return Err("schema = 1 is missing".into()),
    }
    closed(root, &ROOT_KEYS, "")?;
    if let Some(platform) = root.get("platform") {
        check_platform(platform)?;
    }
    match root.get("apps") {
        None => Ok(()),
        Some(Value::Array(rows)) => rows.iter().enumerate().try_for_each(|(i, r)| check_row(r, &format!("apps[{i}]."))),
        Some(_) => Err("apps must be [[apps]] rows".into()),
    }
}

fn check_platform(value: &Value) -> Checked {
    let table = value.as_table().ok_or("[platform] must be a table")?;
    closed(table, &PLATFORM_KEYS, "platform.")?;
    if let Some(api) = table.get("api") {
        let ok = api.as_str().is_some_and(|s| s.starts_with("https://") || s.starts_with("http://"));
        if !ok {
            return Err("platform.api must be an http(s) URL".into());
        }
    }
    nonempty(table, "profile", "platform.")
}

fn nonempty(table: &toml::Table, key: &str, at: &str) -> Checked {
    match table.get(key) {
        None => Ok(()),
        Some(Value::String(s)) if !s.is_empty() => Ok(()),
        Some(_) => Err(format!("{at}{key} must be a non-empty string")),
    }
}

fn check_row(value: &Value, at: &str) -> Checked {
    let row = value.as_table().ok_or_else(|| format!("{at} must be a table"))?;
    closed(row, &ROW_KEYS, at)?;
    if !row.get("name").and_then(Value::as_str).is_some_and(app_name_ok) {
        return Err(format!("{at}name must be a lowercase letter, then up to 39 lowercase letters, digits or hyphens"));
    }
    if row.get("path").is_none() {
        return Err(format!("{at}path is missing"));
    }
    for key in ["id", "path", "start", "manifest", "manifest_command", "env_file"] {
        nonempty(row, key, at)?;
    }
    if row.get("port").is_some_and(|p| !p.as_integer().is_some_and(|n| (1..=65535).contains(&n))) {
        return Err(format!("{at}port must be a whole number from 1 to 65535"));
    }
    if row.get("render_path").is_some_and(|p| !p.as_str().is_some_and(|s| s.starts_with('/'))) {
        return Err(format!("{at}render_path must start with /"));
    }
    if row.contains_key("manifest") && row.contains_key("manifest_command") {
        return Err(format!("{at}manifest and manifest_command are one or the other"));
    }
    check_credentials(row, at)?;
    check_env(row, at)
}

fn check_credentials(row: &toml::Table, at: &str) -> Checked {
    let Some(value) = row.get("credentials") else { return Ok(()) };
    let items = value.as_array().ok_or_else(|| format!("{at}credentials must be a list"))?;
    let mut seen = Vec::new();
    for item in items {
        let ok = item.as_str().filter(|s| CREDENTIALS.contains(s) && !seen.contains(s));
        seen.push(ok.ok_or_else(|| format!("{at}credentials takes app_secret and client_credentials, each once"))?);
    }
    Ok(())
}

fn check_env(row: &toml::Table, at: &str) -> Checked {
    let Some(value) = row.get("env") else { return Ok(()) };
    let table = value.as_table().ok_or_else(|| format!("{at}env must be a table"))?;
    match table.iter().find(|(_, v)| !v.is_str()) {
        Some((k, _)) => Err(format!("{at}env.{k} must be a string")),
        None => Ok(()),
    }
}
