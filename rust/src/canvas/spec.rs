//! A field node's op body: the web's `specFromFields` then `specBody`
//! (`testRun.ts`, `plan.ts`), over the node's saved fields. A create's
//! client is always `billing_mode: allowance`; no body carries `enabled`,
//! `api_version` or `client_type`. The app manifest is `lingara.v1`'s
//! `manifest()` wire form, with the defaults it fills.

use serde_json::{Map, Value, json};

/// Why a node's fields are not its kind's spec.
pub type Invalid = String;

fn text(v: &Value, what: &str) -> Result<String, Invalid> {
    v.as_str().map(str::to_string).ok_or_else(|| format!("{what} must be a string"))
}

fn texts(v: &Value, what: &str) -> Result<Vec<String>, Invalid> {
    let items = v.as_array().ok_or_else(|| format!("{what} must be an array of strings"))?;
    items.iter().map(|i| text(i, what)).collect()
}

fn optional_texts(v: &Value, what: &str) -> Result<Option<Vec<String>>, Invalid> {
    if v.is_null() { Ok(None) } else { texts(v, what).map(Some) }
}

/// A plain string is `{locale: s}`; a map must hold strings only.
fn locale_text(v: &Value, locale: &str, what: &str) -> Result<Value, Invalid> {
    match v {
        Value::String(s) => Ok(json!({ locale: s })),
        Value::Object(m) if m.values().all(Value::is_string) => Ok(v.clone()),
        _ => Err(format!("{what} must be a string or an object of strings")),
    }
}

/// `{name, scopes, redirect_uris?}` plus `billing_mode` on a create.
pub fn client(fields: &Value, create: bool) -> Result<Value, Invalid> {
    let mut body = json!({ "name": text(&fields["name"], "name")?, "scopes": texts(&fields["scopes"], "scopes")? });
    if let Some(uris) = optional_texts(&fields["redirectUris"], "redirectUris")? {
        body["redirect_uris"] = json!(uris);
    }
    if create {
        body["billing_mode"] = json!("allowance");
    }
    Ok(body)
}

pub fn webhook(fields: &Value) -> Result<Value, Invalid> {
    Ok(json!({ "url": text(&fields["url"], "url")?, "event_types": texts(&fields["events"], "events")? }))
}

pub fn app(fields: &Value) -> Result<Value, Invalid> {
    Ok(json!({ "manifest": manifest(&fields["manifest"])? }))
}

/// `lingara.v1`'s `manifest()`, in the order it writes the members.
pub fn manifest(o: &Value) -> Result<Value, Invalid> {
    if !o.is_object() {
        return Err("manifest must be an object".into());
    }
    let locale = if o["defaultLocale"].is_null() { "en".to_string() } else { text(&o["defaultLocale"], "defaultLocale")? };
    let mut m = Map::new();
    m.insert("manifest_version".into(), json!(1));
    m.insert("default_locale".into(), json!(locale));
    m.insert("name".into(), locale_text(&o["name"], &locale, "name")?);
    m.insert("description".into(), locale_text(&o["description"], &locale, "description")?);
    m.insert("render_url".into(), json!(text(&o["renderUrl"], "renderUrl")?));
    m.insert("slots".into(), json!(texts(&o["slots"], "slots")?));
    m.insert("context".into(), json!(optional_texts(&o["context"], "context")?.unwrap_or_default()));
    m.insert("scopes".into(), json!(optional_texts(&o["scopes"], "scopes")?.unwrap_or_default()));
    m.insert("tutor_note".into(), json!(o["tutorNote"].as_bool().unwrap_or(false)));
    listing(o, &locale, &mut m)?;
    Ok(Value::Object(m))
}

/// The listing's three members, each written only when its option is set.
fn listing(o: &Value, locale: &str, m: &mut Map<String, Value>) -> Result<(), Invalid> {
    if !o["longDescription"].is_null() {
        m.insert("long_description".into(), locale_text(&o["longDescription"], locale, "longDescription")?);
    }
    if let Some(dev) = o["developer"].as_object() {
        let mut d = json!({ "name": text(&dev.get("name").cloned().unwrap_or_default(), "developer.name")? });
        for (from, to) in [("url", "url"), ("supportUrl", "support_url")] {
            if let Some(v) = dev.get(from) {
                d[to] = json!(text(v, from)?);
            }
        }
        m.insert("developer".into(), d);
    }
    if let Some(images) = o["images"].as_array() {
        let wire: Result<Vec<Value>, Invalid> = images
            .iter()
            .map(|i| Ok(json!({ "sha256": text(&i["sha256"], "images[].sha256")?, "role": text(&i["role"], "images[].role")?, "alt": locale_text(&i["alt"], locale, "images[].alt")? })))
            .collect();
        m.insert("images".into(), json!(wire?));
    }
    Ok(())
}

/// The name an app op registers under: the manifest's in its default locale.
pub fn app_name(body: &Value, fallback: &str) -> String {
    let manifest = &body["manifest"];
    let locale = manifest["default_locale"].as_str().unwrap_or("en");
    let names = &manifest["name"];
    names[locale].as_str().or_else(|| names.as_object().and_then(|m| m.values().find_map(Value::as_str))).unwrap_or(fallback).to_string()
}
