//! Every shared file this kit reads, compiled in from its own `assets/`
//! (ADR 16.10.26ae D12): `endpoints.json` (where production is),
//! `messages.en.json` (what a prompt says, byte for byte), `tools.toml`
//! (cloudflared's floor) and the app templates, which build.rs indexes. All
//! are committed copies held equal to their sources by `make
//! check-kit-assets`, because a published crate carries only its own
//! directory.

use std::collections::BTreeMap;
use std::sync::LazyLock;

use serde::Deserialize;

const MESSAGES: &str = include_str!("../assets/messages.en.json");
const ENDPOINTS: &str = include_str!("../assets/endpoints.json");
const TOOLS: &str = include_str!("../assets/tools.toml");

mod generated {
    include!(concat!(env!("OUT_DIR"), "/assets.rs"));
}

#[derive(Deserialize)]
struct MessageFile {
    messages: Vec<Message>,
}

#[derive(Deserialize)]
struct Message {
    id: String,
    text: String,
}

static TEXTS: LazyLock<BTreeMap<String, String>> = LazyLock::new(|| {
    let file: Result<MessageFile, _> = serde_json::from_str(MESSAGES);
    file.map(|f| f.messages.into_iter().map(|m| (m.id, m.text)).collect()).unwrap_or_default()
});

/// The message `id` with each `{name}` filled; an id the file lacks prints
/// as itself, which a test pins never to happen.
pub fn message(id: &str, vars: &[(&str, &str)]) -> String {
    let text = TEXTS.get(id).cloned().unwrap_or_else(|| id.to_string());
    vars.iter().fold(text, |t, (k, v)| t.replace(&format!("{{{k}}}"), v))
}

/// Whether `messages.en.json` carries `id`.
#[cfg(test)]
pub fn has_message(id: &str) -> bool {
    TEXTS.contains_key(id)
}

#[derive(Debug, Clone, Deserialize)]
pub struct Endpoints {
    /// The first-party public client `lingara login` signs in as (16.10.26u §3).
    pub client_id: String,
    pub api_url: String,
    pub platform_url: String,
}

pub fn endpoints() -> Endpoints {
    serde_json::from_str(ENDPOINTS).unwrap_or_else(|_| Endpoints {
        client_id: "lingara-cli".into(),
        api_url: "https://api.getlingara.com".into(),
        platform_url: "https://platform.getlingara.com".into(),
    })
}

/// `tools.toml`'s `[cloudflared] min_version`, as dotted numbers.
pub fn cloudflared_min_version() -> Vec<u64> {
    let parsed: Option<toml::Value> = toml::from_str(TOOLS).ok();
    let text = parsed.as_ref().and_then(|v| v["cloudflared"]["min_version"].as_str().map(str::to_string)).unwrap_or_default();
    text.split('.').filter_map(|n| n.parse().ok()).collect()
}

/// Every file of one template (`<lang>/<variant>/`), path relative to it.
pub fn template(lang: &str, variant: &str) -> Vec<(&'static str, &'static [u8])> {
    let prefix = format!("{lang}/{variant}/");
    let strip = |p: &'static str| p.strip_prefix(prefix.as_str());
    generated::TEMPLATES.iter().filter_map(|(path, bytes)| strip(path).map(|p| (p, *bytes))).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_asset_parses() {
        assert!(serde_json::from_str::<Endpoints>(ENDPOINTS).is_ok());
        assert!(has_message("confirm_yes_no") && has_message("platform_disabled") && has_message("apps_dev_ready"));
        assert_eq!(message("login_signed_in", &[("email", "a@b.c")]), "Signed in as a@b.c");
        assert_eq!(cloudflared_min_version().len(), 3);
        let ts = template("typescript", "default");
        assert!(ts.iter().any(|(p, _)| *p == "template.toml") && ts.iter().any(|(p, _)| *p == "lingara/connection.js"));
        assert!(template("php", "bare").iter().all(|(p, _)| *p != "lingara/connection.js"));
    }
}
