//! B2's classification order, rule by rule, with B4's three signals first.
//! Each fault breaks exactly the rule its name says.

use serde_json::Value;

use crate::net::Resp;
use crate::{Fail, Script, exit};

const PLATFORM_DISABLED: &str = "platform_disabled";
const PLATFORM_ACCESS: &str = "platform_access_not_enabled";
pub const PLATFORM_DISABLED_TEXT: &str = "The Lingara platform isn't enabled for this account yet.";

fn str_of<'a>(body: &'a Option<Value>, key: &str) -> Option<&'a str> {
    body.as_ref().and_then(|b| b.get(key)).and_then(Value::as_str)
}

/// `/oauth/token`'s spelling: keyed on `lingara_code`, never on English
/// text (unless a fault says so).
fn token_signal(script: &Script, body: &Option<Value>) -> bool {
    if script.has("invalid_grant_is_8") && str_of(body, "error") == Some("invalid_grant") {
        return true;
    }
    if script.has("key_on_description") {
        return str_of(body, "error_description").is_some_and(|d| d.contains("not enabled"));
    }
    str_of(body, "lingara_code") == Some(PLATFORM_ACCESS)
}

/// B4's three signals.
fn platform_disabled(script: &Script, resp: &Resp, body: &Option<Value>, token_route: bool) -> bool {
    let code = str_of(body, "code");
    let keeps_dark = !script.has("dark_is_not_found") && !script.has("raw_404");
    let dark = resp.status == 404 && code == Some(PLATFORM_DISABLED) && keeps_dark;
    let door = resp.status == 403 && code == Some(PLATFORM_ACCESS);
    dark || door || (token_route && token_signal(script, body))
}

pub fn classify(script: &Script, resp: &Resp, route: &str) -> Fail {
    let body = resp.json();
    let token_route = route == "oauth.token" || route == "oauth.device_authorization";
    if platform_disabled(script, resp, &body, token_route) {
        return Fail::new(exit::PLATFORM_DISABLED, PLATFORM_DISABLED, PLATFORM_DISABLED_TEXT);
    }
    if script.has("raw_404") && resp.status == 404 {
        return Fail::new(exit::NOT_FOUND, "not_found", format!("HTTP 404 {}", String::from_utf8_lossy(&resp.body)));
    }
    by_status(resp.status, &body, token_route, route)
}

/// Rules 2–8, once B4 has had its turn.
fn by_status(status: u16, body: &Option<Value>, token_route: bool, route: &str) -> Fail {
    let code = str_of(body, "code");
    let text = str_of(body, "error").or(str_of(body, "error_description")).unwrap_or("").to_string();
    let oauth_envelope = token_route && (400..500).contains(&status) && status != 429 && code.is_none();
    match status {
        401 => Fail::new(exit::AUTH_REQUIRED, "auth_required", "sign-in expired: run `lingara login`"),
        _ if oauth_envelope && str_of(body, "error").is_some() => Fail::new(exit::AUTH_REQUIRED, "auth_required", text),
        404 if code == Some("developer_not_found") && route == "developer.me" => Fail::new(exit::REFUSED, "developer_not_found", text),
        404 if code.is_some() => Fail::new(exit::NOT_FOUND, code.unwrap_or("not_found"), text),
        400 | 403 | 409 | 422 => Fail::new(exit::REFUSED, code.unwrap_or("refused"), text),
        429 | 500..=599 => Fail::new(exit::UNAVAILABLE, "unavailable", format!("{route} answered {status}")),
        _ => Fail::new(exit::FAILURE, "failure", format!("unexpected response from {route}")),
    }
}
