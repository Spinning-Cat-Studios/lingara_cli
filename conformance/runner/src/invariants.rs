//! The invariants judged on every case, whether or not it asks (ADR
//! 16.10.26ac D6). I1 is the release plan's guard: no destructive call
//! without confirmation, on every dangerous command in every language.

use std::sync::LazyLock;

use regex::Regex;
use serde_json::Value;

use crate::case::schema::Schema;
use crate::case::{Case, Confirm, DEV_EMAIL};
use crate::mock::Logged;
use crate::routes::Routes;
use crate::spawn::{BROWSER_AGENT, Outcome};

/// B5's `User-Agent`, checked on every request (I3).
pub static USER_AGENT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^lingara-cli/(\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?) \((typescript|rust|go|java|kotlin|ruby|php); [a-z0-9_]+\)$")
        .expect("the B5 pattern compiles")
});

/// What one run left behind, for the invariants to read.
pub struct Evidence<'a> {
    pub case: &'a Case,
    pub schema: &'a Schema,
    pub routes: &'a Routes,
    pub outcome: &'a Outcome,
    pub log: &'a [Logged],
}

pub fn check(e: &Evidence) -> Vec<String> {
    let mut out = Vec::new();
    i1_confirmation(e, &mut out);
    i2_i3_requests(e, &mut out);
    i4_json(e, &mut out);
    i5_exit(e, &mut out);
    i6_secrets(e, &mut out);
    out
}

/// Trimmed and ASCII-lowercased: the form a kit sends and I1 compares.
pub fn canonical(email: &str) -> String {
    email.trim().to_ascii_lowercase()
}

/// The `--confirm` value, when argv carries `-y`/`--yes` too.
pub fn flagged_email(argv: &[String]) -> Option<String> {
    let yes = argv.iter().any(|a| a == "-y" || a == "--yes");
    let confirm = argv.iter().enumerate().find_map(|(i, a)| match a.strip_prefix("--confirm") {
        Some("") => argv.get(i + 1).cloned(),
        Some(rest) => rest.strip_prefix('=').map(str::to_string),
        None => None,
    });
    confirm.filter(|_| yes)
}

fn i1_confirmation(e: &Evidence, out: &mut Vec<String>) {
    let dangerous = e.case.command.as_deref().and_then(|c| e.schema.command(c)).is_some_and(|c| c.dangerous);
    if !dangerous {
        return;
    }
    let destructive: Vec<&Logged> =
        e.log.iter().filter(|l| e.routes.is_destructive(&l.request.method, &l.request.path)).collect();
    if e.case.confirm == Some(Confirm::NonTtyRefused) && !e.log.is_empty() {
        out.push(format!("I1: {} request(s) sent on a non_tty_refused case; expected none", e.log.len()));
    }
    let refused = matches!(
        e.case.confirm,
        Some(Confirm::NonTtyRefused | Confirm::NonTtyFlagsMismatch | Confirm::TtyDeclined | Confirm::TtyMismatch)
    );
    if refused && !destructive.is_empty() {
        out.push(format!("I1: {} destructive request(s) on an unconfirmed case; expected none", destructive.len()));
    }
    for d in destructive {
        i1_one(e, d, out);
    }
}

fn i1_one(e: &Evidence, d: &Logged, out: &mut Vec<String>) {
    let at = format!("{} {}", d.request.method, d.request.path);
    let flagged = flagged_email(&e.case.argv).is_some_and(|m| canonical(&m) == DEV_EMAIL);
    let all_sent = e.outcome.sends == e.case.sends() && e.case.sends() > 0;
    let after_send = e.case.tty && all_sent && e.outcome.last_send.is_some_and(|t| d.at > t);
    if !(flagged || after_send) {
        out.push(format!("I1: {at} without confirmation (no matching -y --confirm, and not after the last interact send)"));
    }
    let body = serde_json::from_slice::<Value>(&d.request.body).ok();
    let field = body.as_ref().and_then(|b| b.get(&e.routes.confirm_body_field));
    if field.and_then(Value::as_str) != Some(DEV_EMAIL) {
        out.push(format!(
            "I1: {at} must carry {{\"{}\": \"{DEV_EMAIL}\"}}, got body {:?}",
            e.routes.confirm_body_field,
            String::from_utf8_lossy(&d.request.body)
        ));
    }
}

fn i2_i3_requests(e: &Evidence, out: &mut Vec<String>) {
    for l in e.log {
        let at = format!("{} {}", l.request.method, l.request.path);
        if let Some(reason) = &l.mismatch {
            out.push(format!("I2: answered 599: {reason}"));
        }
        match l.request.header("user-agent") {
            Some(ua) if USER_AGENT.is_match(ua) || ua == BROWSER_AGENT => {}
            ua => out.push(format!("I3: {at}: User-Agent {ua:?} does not match B5's pattern")),
        }
        let delete_body = l.request.method == "DELETE" && !l.request.body.is_empty();
        if delete_body && !e.routes.is_destructive(&l.request.method, &l.request.path) {
            out.push(format!("B5: {at}: a DELETE carries a body, and the route is not destructive"));
        }
    }
}

fn i4_json(e: &Evidence, out: &mut Vec<String>) {
    if !e.case.json_output() {
        return;
    }
    let ok = e.schema.exits.get("ok").copied();
    let stdout = String::from_utf8_lossy(&e.outcome.stdout);
    if stdout.trim().is_empty() && e.outcome.exit != ok {
        return;
    }
    let values = serde_json::Deserializer::from_str(&stdout).into_iter::<Value>().collect::<Result<Vec<_>, _>>();
    match values {
        Ok(v) if v.len() == 1 => {}
        Ok(v) => out.push(format!("I4: --json stdout holds {} JSON values; expected exactly one", v.len())),
        Err(err) => out.push(format!("I4: --json stdout is not JSON ({err}): {stdout:?}")),
    }
}

fn i5_exit(e: &Evidence, out: &mut Vec<String>) {
    match e.outcome.exit {
        Some(code) if e.schema.exit_name(code).is_some() => {}
        Some(code) => out.push(format!("I5: exit {code} is not in the D3 table")),
        None => out.push("I5: no exit code (killed, timed out or never ran)".into()),
    }
}

/// Every secret the mock handed out: `lgr_cs_`, `lgr_at_` and refresh tokens,
/// and since ADR 16.10.26ae D11 the `lgr_whsec_` app secrets `platform_dev`
/// mints per session.
fn issued(log: &[Logged]) -> Vec<String> {
    fn walk(v: &Value, key: Option<&str>, out: &mut Vec<String>) {
        let secret = |s: &str| ["lgr_cs_", "lgr_at_", "lgr_whsec_"].iter().any(|p| s.starts_with(p));
        match v {
            Value::String(s) if secret(s) || key == Some("refresh_token") => {
                out.push(s.clone());
            }
            Value::Array(a) => a.iter().for_each(|v| walk(v, None, out)),
            Value::Object(o) => o.iter().for_each(|(k, v)| walk(v, Some(k), out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    log.iter().filter_map(|l| l.answer.as_ref()).for_each(|a| walk(a, None, &mut out));
    out
}

fn i6_secrets(e: &Evidence, out: &mut Vec<String>) {
    let stdout = String::from_utf8_lossy(&e.outcome.stdout);
    let named = format!("{:?}", e.case.expect.stdout);
    let mut secrets = e.case.expect.redacted.clone();
    secrets.extend(issued(e.log));
    for secret in secrets.iter().filter(|s| !s.is_empty()) {
        if e.outcome.stderr.contains(secret.as_str()) {
            out.push(format!("I6: secret {secret:?} appears on stderr"));
        }
        if stdout.contains(secret.as_str()) && !named.contains(secret.as_str()) {
            out.push(format!("I6: secret {secret:?} appears on stdout, and expect.stdout does not name it"));
        }
    }
}
