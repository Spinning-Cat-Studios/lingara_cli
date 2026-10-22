//! The closed key map from `lingara.v1`'s `ManifestOptions` to the wire
//! manifest (spec/connection-literal.md), in `lingara.manifest`'s member
//! order. A key the map does not name is refused, never passed through.

use serde_json::{Map, Value, json};

use super::{Lit, Member, Pos, Refusal};

/// Literal key → wire key, in the order the wire manifest writes them.
const KEYS: [(&str, &str); 11] = [
    ("defaultLocale", "default_locale"),
    ("name", "name"),
    ("description", "description"),
    ("renderUrl", "render_url"),
    ("slots", "slots"),
    ("context", "context"),
    ("scopes", "scopes"),
    ("tutorNote", "tutor_note"),
    ("longDescription", "long_description"),
    ("developer", "developer"),
    ("images", "images"),
];
const REQUIRED: [&str; 4] = ["name", "description", "renderUrl", "slots"];
const DEVELOPER: [(&str, &str); 3] = [("name", "name"), ("url", "url"), ("supportUrl", "support_url")];
const IMAGE: [(&str, &str); 3] = [("sha256", "sha256"), ("role", "role"), ("alt", "alt")];

fn find<'a>(members: &'a [Member], key: &str) -> Option<&'a Lit> {
    members.iter().find(|m| m.key == key).map(|m| &m.value)
}

/// A string is the default locale's; an object passes its tags through.
fn locale_map(lit: &Lit, locale: &str) -> Value {
    match lit {
        Lit::Str(s) => json!({ locale: s }),
        other => other.to_json(),
    }
}

/// An object whose keys must all be in `map`, renamed through it.
fn renamed(lit: &Lit, map: &[(&str, &str)], locale: &str) -> Result<Value, Refusal> {
    let Lit::Obj(members, _) = lit else { return Ok(lit.to_json()) };
    let mut out = Map::new();
    for (from, to) in map {
        if let Some(value) = find(members, from) {
            out.insert((*to).to_string(), if *from == "alt" { locale_map(value, locale) } else { value.to_json() });
        }
    }
    match members.iter().find(|m| !map.iter().any(|(k, _)| *k == m.key)) {
        Some(stray) => Err(Refusal::at("manifest_key", stray.at)),
        None => Ok(Value::Object(out)),
    }
}

fn images(lit: &Lit, locale: &str) -> Result<Value, Refusal> {
    let Lit::Arr(items) = lit else { return Ok(lit.to_json()) };
    items.iter().map(|i| renamed(i, &IMAGE, locale)).collect::<Result<Vec<_>, _>>().map(Value::Array)
}

/// The wire manifest for the `manifest` object at `at`.
pub fn convert(members: &[Member], at: Pos) -> Result<Value, Refusal> {
    if let Some(stray) = members.iter().find(|m| !KEYS.iter().any(|(k, _)| *k == m.key)) {
        return Err(Refusal::at("manifest_key", stray.at));
    }
    if REQUIRED.iter().any(|k| find(members, k).is_none()) {
        return Err(Refusal::at("manifest_key", at));
    }
    let locale = match find(members, "defaultLocale") {
        Some(Lit::Str(s)) => s.clone(),
        _ => "en".to_string(),
    };
    let mut out = Map::new();
    out.insert("manifest_version".into(), json!(1));
    out.insert("default_locale".into(), find(members, "defaultLocale").map_or_else(|| json!("en"), Lit::to_json));
    for (from, to) in &KEYS[1..] {
        let value = match (find(members, from), *from) {
            (None, "context" | "scopes") => json!([]),
            (None, "tutorNote") => json!(false),
            (None, _) => continue,
            (Some(v), "name" | "description" | "longDescription") => locale_map(v, &locale),
            (Some(v), "developer") => renamed(v, &DEVELOPER, &locale)?,
            (Some(v), "images") => images(v, &locale)?,
            (Some(v), _) => v.to_json(),
        };
        out.insert((*to).to_string(), value);
    }
    Ok(Value::Object(out))
}
