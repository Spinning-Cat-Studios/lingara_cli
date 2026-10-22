//! The one response classifier (ADR 16.10.26ad D8; contract B2). Its eight
//! rules are **data**: an ordered list of `(predicate, verdict)` entries,
//! the first match winning and rule 8 the catch-all. 16.10.26ae D10's rule
//! 4a is one more entry, after rule 4.

use serde_json::Value;

use crate::assets::message;
use crate::exit::{Exit, Fail};

/// `conformance/routes.toml`'s two codes, as this kit keys them.
pub const PLATFORM_DISABLED_CODE: &str = "platform_disabled";
pub const PLATFORM_ACCESS_CODE: &str = "platform_access_not_enabled";

/// A non-2xx answer, with what the rules read about the request behind it.
pub struct Answer<'a> {
    pub route: &'a str,
    /// `oauth.token` or `oauth.device_authorization`.
    pub token_route: bool,
    /// Under `/platform/`, where the dark 404 is spoken.
    pub platform_route: bool,
    /// The request carried `Authorization: Bearer` (rule 2).
    pub bearer: bool,
    pub status: u16,
    pub body: &'a [u8],
    pub retry_after: Option<u64>,
}

impl Answer<'_> {
    fn json(&self) -> Option<Value> {
        serde_json::from_slice(self.body).ok()
    }

    fn member(&self, key: &str) -> Option<String> {
        self.json()?.get(key)?.as_str().map(str::to_string)
    }

    /// `{code, error}`, the platform's and `/v1`'s envelope.
    fn coded(&self) -> bool {
        self.member("code").is_some() && self.member("error").is_some()
    }

    /// RFC 6749 / 8628: an `error` member and no `code`.
    fn oauth_envelope(&self) -> bool {
        self.member("error").is_some() && self.member("code").is_none()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Rule 2: refresh once (re-mint under CI), then replay once.
    Refresh,
    Exit(Exit),
}

struct Rule {
    matches: fn(&Answer) -> bool,
    verdict: fn(&Answer) -> Verdict,
}

fn platform_disabled(a: &Answer) -> bool {
    let code = a.member("code");
    let dark = a.status == 404 && a.platform_route && code.as_deref() == Some(PLATFORM_DISABLED_CODE);
    let door = a.status == 403 && code.as_deref() == Some(PLATFORM_ACCESS_CODE);
    let token = a.token_route && a.member("lingara_code").as_deref() == Some(PLATFORM_ACCESS_CODE);
    dark || door || token
}

/// Rule 4's one exception is keyed on route **and** code.
fn coded_not_found(a: &Answer) -> Verdict {
    let developer_gone = a.route == "developer.me" && a.member("code").as_deref() == Some("developer_not_found");
    Verdict::Exit(if developer_gone { Exit::Refused } else { Exit::NotFound })
}

/// Rule 4a (16.10.26ae D10): a dev-session `409` is a row of its own, keyed
/// on `(route, body code)`, because a script branches on "someone else is
/// developing this app" where it would not on any other refusal.
const DEV_SESSION_CODES: [(&str, &str, Exit); 4] = [
    ("app_dev.session_open", "dev_session_held", Exit::DevSessionHeld),
    ("app_dev.patch", "dev_session_held", Exit::DevSessionHeld),
    ("app_dev.session_renew", "dev_session_not_live", Exit::DevSessionLost),
    ("app_dev.session_renew", "dev_session_expired", Exit::DevSessionLost),
];

fn dev_session_row(a: &Answer) -> Option<Exit> {
    let code = a.member("code")?;
    let row = DEV_SESSION_CODES.iter().find(|(route, c, _)| *route == a.route && *c == code)?;
    (a.status == 409).then_some(row.2)
}

const RULES: [Rule; 9] = [
    Rule { matches: platform_disabled, verdict: |_| Verdict::Exit(Exit::PlatformDisabled) },
    Rule { matches: |a| a.status == 401 && a.bearer && !a.token_route, verdict: |_| Verdict::Refresh },
    Rule {
        matches: |a| a.token_route && (400..500).contains(&a.status) && a.status != 429 && a.oauth_envelope(),
        verdict: |_| Verdict::Exit(Exit::AuthRequired),
    },
    Rule { matches: |a| a.status == 404 && a.coded(), verdict: coded_not_found },
    Rule { matches: |a| dev_session_row(a).is_some(), verdict: |a| Verdict::Exit(dev_session_row(a).unwrap_or(Exit::Refused)) },
    Rule { matches: |a| a.status == 404 && a.body.iter().all(u8::is_ascii_whitespace), verdict: |_| Verdict::Exit(Exit::Failure) },
    Rule { matches: |a| matches!(a.status, 400 | 403 | 409 | 422), verdict: |_| Verdict::Exit(Exit::Refused) },
    Rule { matches: |a| a.status == 429 || a.status >= 500, verdict: |_| Verdict::Exit(Exit::Unavailable) },
    Rule { matches: |_| true, verdict: |_| Verdict::Exit(Exit::Failure) },
];

pub fn classify(answer: &Answer) -> Verdict {
    let rule = RULES.iter().find(|r| (r.matches)(answer));
    rule.map_or(Verdict::Exit(Exit::Failure), |r| (r.verdict)(answer))
}

/// The refusal B2 prints for `exit`: the server's own code and text where
/// it sent them, the kit's sentence where it did not.
pub fn fail_for(answer: &Answer, exit: Exit) -> Fail {
    let unexpected = || Fail::new(Exit::Failure, "unexpected_response", format!("unexpected response from {}", answer.route));
    let mut fail = match exit {
        Exit::PlatformDisabled => Fail::new(exit, PLATFORM_DISABLED_CODE, message("platform_disabled", &[])),
        Exit::Failure => unexpected(),
        Exit::AuthRequired => {
            let code = answer.member("error").unwrap_or_else(|| "auth_required".into());
            let text = answer.member("error_description").unwrap_or_else(|| format!("{} refused the credentials", answer.route));
            Fail::new(exit, &code, text)
        }
        _ => {
            let code = answer.member("code").unwrap_or_else(|| exit_word(exit).to_string());
            let text = answer.member("error").unwrap_or_else(|| format!("{} answered HTTP {}", answer.route, answer.status));
            Fail::new(exit, &code, text)
        }
    };
    if let Some(secs) = answer.retry_after.filter(|_| exit == Exit::Unavailable) {
        fail.message = format!("{} (retry after {secs} s)", fail.message);
    }
    // A command that re-words a refusal (apps dev's `dev_session_held`)
    // reads the server's other members from here.
    fail.body = Some(String::from_utf8_lossy(answer.body).into());
    fail
}

fn exit_word(exit: Exit) -> &'static str {
    if exit == Exit::Unavailable { "unavailable" } else { "refused" }
}

#[cfg(test)]
#[path = "classify_tests.rs"]
mod classify_tests;
