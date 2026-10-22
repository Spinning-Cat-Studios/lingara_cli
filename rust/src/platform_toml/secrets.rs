//! D4's `platform_toml_secret`: the first value or key, in file order, that
//! looks like a secret. A value is one when it matches
//! `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id (`lgr_cid_…`);
//! a key is one when its `_`-separated words include `secret`, `token` or
//! `password`, case-insensitively (`CLIENT_SECRET`, `api_token`; never
//! `TOKENIZER`). The refusal names the key, never the value.

use toml::Value;

const SECRET_WORDS: [&str; 3] = ["secret", "token", "password"];

/// Whether `s` holds a real-looking `lgr_` token other than a client id.
pub fn secret_value(s: &str) -> bool {
    let mut rest = s;
    while let Some(at) = rest.find("lgr_") {
        let tail = &rest[at + 4..];
        let kind_len = tail.bytes().take_while(u8::is_ascii_lowercase).count();
        let after = &tail[kind_len..];
        if kind_len > 0 && after.starts_with('_') {
            let body = after[1..].bytes().take_while(u8::is_ascii_alphanumeric).count();
            if body >= 8 && &tail[..kind_len] != "cid" {
                return true;
            }
        }
        rest = tail;
    }
    false
}

pub fn secret_key(key: &str) -> bool {
    key.split('_').any(|w| SECRET_WORDS.iter().any(|s| w.eq_ignore_ascii_case(s)))
}

/// The dotted path of the first secret-shaped key or value, if any.
pub fn first_secret(value: &Value) -> Option<String> {
    walk(value, "")
}

fn walk(value: &Value, at: &str) -> Option<String> {
    match value {
        Value::String(s) if secret_value(s) => Some(at.to_string()),
        Value::Array(items) => items.iter().find_map(|v| walk(v, at)),
        Value::Table(table) => table.iter().find_map(|(k, v)| {
            let path = if at.is_empty() { k.clone() } else { format!("{at}.{k}") };
            if secret_key(k) { Some(path) } else { walk(v, &path) }
        }),
        _ => None,
    }
}
