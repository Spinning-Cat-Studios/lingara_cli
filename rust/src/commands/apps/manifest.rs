//! What the CLI checks in a wire manifest before it sends one (ADR
//! 16.10.26ae D6): the ephemeral render URL a version may never carry, the
//! manifest rules a warning depends on, and the warnings themselves, pinned
//! by `spec/manifest-warnings.json` (the app kits' `manifestWarnings`).
//! The server stays the authority on every rule; these run first so a
//! refusal costs no request.

use serde_json::Value;

/// The slots a manifest may name (the app view's `AppSlotName`).
const SLOTS: [&str; 2] = ["home.side", "plans.empty_detail"];

/// The text each warning prints with, as `manifest_warning`'s `{text}`.
const WARNING_TEXTS: [(&str, &str); 1] = [("single_locale", "the listing is in its default locale only; give name and description a second locale")];

/// The URL's host, lowercased: no scheme, user, port or path.
fn host(url: &str) -> String {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host = authority.rsplit('@').next().unwrap_or("");
    host.split(':').next().unwrap_or("").to_ascii_lowercase()
}

/// A quick tunnel's or the template's placeholder: never a version's URL.
pub fn ephemeral(render_url: &str) -> bool {
    let host = host(render_url);
    host == "dev.invalid" || host == "trycloudflare.com" || host.ends_with(".trycloudflare.com")
}

fn locale_map(v: &Value) -> bool {
    v.as_object().is_some_and(|m| !m.is_empty() && m.values().all(|s| s.as_str().is_some_and(|s| !s.is_empty())))
}

fn has_duplicate(v: &Value) -> bool {
    let items = v.as_array().map(Vec::as_slice).unwrap_or_default();
    items.iter().enumerate().any(|(i, a)| items[..i].contains(a))
}

/// The first rule `m` breaks, of the ones a warning depends on, in the app
/// kits' order; `None` when it keeps them all.
pub fn broken_rule(m: &Value) -> Option<&'static str> {
    let locale = m["default_locale"].as_str().unwrap_or("");
    let in_map = |key: &str| m[key].as_object().is_none_or(|o| o.contains_key(locale));
    let slots_ok = m["slots"].as_array().is_some_and(|s| !s.is_empty() && s.iter().all(|x| x.as_str().is_some_and(|x| SLOTS.contains(&x))));
    let context_ok = m.get("context").is_none_or(|c| c.as_array().is_some_and(|a| a.iter().all(Value::is_string)));
    let checks: [(&str, bool); 9] = [
        ("manifest_version", m["manifest_version"] == 1),
        ("default_locale", !locale.is_empty() && in_map("name") && in_map("description")),
        ("name", locale_map(&m["name"])),
        ("description", locale_map(&m["description"])),
        ("render_url", m["render_url"].as_str().is_some_and(|u| u.len() > 8 && u[..8].eq_ignore_ascii_case("https://") && !host(u).is_empty())),
        ("slots", slots_ok),
        ("context", context_ok),
        ("locales", m.get("locales").is_none()),
        ("duplicate", !["slots", "context", "scopes"].iter().any(|k| has_duplicate(&m[*k]))),
    ];
    checks.iter().find(|(_, kept)| !kept).map(|(rule, _)| *rule)
}

/// Where `name` and `description` both have an entry, and
/// `long_description` too when present and not null, in byte order; empty
/// for a manifest the rules refuse.
pub fn locales(m: &Value) -> Vec<String> {
    if broken_rule(m).is_some() {
        return Vec::new();
    }
    let keys = |key: &str| m[key].as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()).unwrap_or_default();
    let long = &m["long_description"];
    let covered = |l: &String| keys("description").contains(l) && (long.is_null() || keys("long_description").contains(l));
    let mut out: Vec<String> = keys("name").into_iter().filter(covered).collect();
    out.sort();
    out
}

/// The listing's warnings: v1 `single_locale` alone. Never a refusal.
pub fn warnings(m: &Value) -> Vec<&'static str> {
    let locales = locales(m);
    let only_default = locales.len() == 1 && m["default_locale"].as_str() == Some(locales[0].as_str());
    if only_default { vec!["single_locale"] } else { Vec::new() }
}

pub fn warning_text(code: &str) -> &'static str {
    WARNING_TEXTS.iter().find(|(c, _)| *c == code).map_or("", |(_, t)| t)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 16.10.26ae AC17: every vector in spec/manifest-warnings.json gives its
    /// expected locales and warnings.
    #[test]
    fn manifest_warnings_follow_the_shared_vectors() {
        let file: Value = serde_json::from_str(include_str!("../../../assets/manifest-warnings.json")).unwrap();
        for v in file["vectors"].as_array().unwrap() {
            let name = v["name"].as_str().unwrap();
            assert_eq!(serde_json::json!(locales(&v["manifest"])), v["expect"]["locales"], "{name}");
            assert_eq!(serde_json::json!(warnings(&v["manifest"])), v["expect"]["warnings"], "{name}");
        }
    }

    #[test]
    fn quick_tunnels_and_the_placeholder_are_ephemeral() {
        assert!(ephemeral("https://dev.invalid/") && ephemeral("https://a-b.trycloudflare.com/render"));
        assert!(ephemeral("https://user@X.TryCloudflare.com:443/"));
        assert!(!ephemeral("https://cards.example.com/render") && !ephemeral("https://trycloudflare.com.example/"));
    }
}
