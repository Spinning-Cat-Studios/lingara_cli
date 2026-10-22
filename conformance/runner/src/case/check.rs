//! What a case must hold beyond its closed parse (ADR 16.10.26ac D3, D7
//! rule 1): every name it uses exists, its modes do not mix, and its argv
//! resolves to the command it claims to cover.

use super::schema::Schema;
use super::{Case, Exchange, Loaded, StreamMatch, TextMatch};
use crate::routes::Routes;
use crate::spawn::RUNNER_ENV;

/// Every refusal for one case, each prefixed with its id and path.
pub fn check(loaded: &Loaded, schema: &Schema, routes: &Routes) -> Vec<String> {
    let case = &loaded.case;
    let mut out = Vec::new();
    check_command(case, schema, &mut out);
    check_modes(case, schema, &mut out);
    check_env(case, &mut out);
    check_apps_keys(case, schema, routes, &mut out);
    check_messages(case, schema, &mut out);
    for (i, item) in case.exchanges.iter().flat_map(|x| &x.items).enumerate() {
        check_item(i, item, routes, &mut out);
    }
    let at = format!("{} ({})", case.id, loaded.path.display());
    out.into_iter().map(|e| format!("{at}: {e}")).collect()
}

fn check_command(case: &Case, schema: &Schema, out: &mut Vec<String>) {
    if !schema.exits.contains_key(&case.expect.exit) {
        out.push(format!("exit `{}` is not a D3 exit name", case.expect.exit));
    }
    let resolved = schema.resolve(&case.argv).map(|c| c.path.as_str());
    let Some(name) = &case.command else {
        if let Some(path) = resolved {
            out.push(format!("argv resolves to `{path}`, so `command` is required"));
        } else if case.expect.exit != "usage" {
            out.push("a case without `command` must expect `usage`".into());
        }
        return;
    };
    let Some(command) = schema.command(name) else {
        out.push(format!("command `{name}` is not in the schema"));
        return;
    };
    if resolved != Some(name.as_str()) {
        out.push(format!("argv {:?} does not resolve to `{name}`", case.argv));
    }
    if schema.exits.contains_key(&case.expect.exit) && !schema.allows_exit(command, &case.expect.exit) {
        out.push(format!("exit `{}` is not one `{name}` can return", case.expect.exit));
    }
}

fn check_modes(case: &Case, schema: &Schema, out: &mut Vec<String>) {
    if case.tty && case.stdin.is_some() {
        out.push("`stdin` is for a non-tty case only".into());
    }
    let terminal_steps = case.interact.iter().any(|i| i.expect.is_some() || i.send.is_some());
    if !case.tty && terminal_steps {
        out.push("`interact.expect` and `send` are for a tty case only".into());
    }
    for step in &case.interact {
        let keys = [step.expect.is_some() || step.send.is_some(), step.browser.is_some()];
        if keys.iter().filter(|k| **k).count() != 1 {
            out.push("an interact step is either expect/send or browser".into());
        }
    }
    let dangerous = case.command.as_deref().and_then(|c| schema.command(c)).is_some_and(|c| c.dangerous);
    if case.confirm.is_some() && !dangerous {
        out.push("`confirm` is for a dangerous command only".into());
    }
}

fn check_env(case: &Case, out: &mut Vec<String>) {
    for name in case.env.keys() {
        if !name.starts_with("LINGARA_") {
            out.push(format!("env `{name}`: a case sets LINGARA_* names only"));
        } else if RUNNER_ENV.contains(&name.as_str()) {
            out.push(format!("env `{name}` is one the runner sets itself"));
        }
    }
}

/// ADR 16.10.26ae D11: its keys in the `apps` group only, the timeout in
/// range, and every name they use existing.
fn check_apps_keys(case: &Case, schema: &Schema, routes: &Routes, out: &mut Vec<String>) {
    let keys = case.apps_keys();
    if case.group() != "apps" {
        out.extend(keys.iter().map(|k| format!("`{k}` is for an `apps` case only")));
        return;
    }
    if let Some(secs) = case.exit_timeout_secs.filter(|s| !super::apps::EXIT_TIMEOUT_RANGE.contains(s)) {
        out.push(format!("exit_timeout_secs {secs} is outside 30–90"));
    }
    if case.mock_seed.is_some() && case.mock.is_none() {
        out.push("`mock_seed` needs `mock`".into());
    }
    for call in case.expect.calls.iter().flatten() {
        if !call.route.starts_with("app_dev.") || routes.get(&call.route).is_none() {
            out.push(format!("expect.calls: `{}` is not an app_dev route", call.route));
        }
    }
    if let Some(signal) = &case.signal
        && !schema.messages.contains_key(&signal.after.message)
    {
        out.push(format!("signal.after: message `{}` is not in messages.en.json", signal.after.message));
    }
}

fn check_messages(case: &Case, schema: &Schema, out: &mut Vec<String>) {
    let mut ids: Vec<&str> = case.interact.iter().filter_map(|i| i.expect.as_ref()).flat_map(text_ids).collect();
    if let Some(m) = &case.expect.stderr {
        ids.extend(m.message.as_deref());
        stream_keys("stderr", m, &["message", "json_error", "contains", "empty"], out);
    }
    if let Some(m) = &case.expect.stdout {
        stream_keys("stdout", m, &["empty", "json", "table", "contains"], out);
    }
    for id in ids.into_iter().filter(|id| !schema.messages.contains_key(*id)) {
        out.push(format!("message `{id}` is not in messages.en.json"));
    }
}

fn text_ids(m: &TextMatch) -> Option<&str> {
    m.message.as_deref()
}

/// A stream matcher has exactly one key, and only a key its stream takes.
fn stream_keys(stream: &str, m: &StreamMatch, allowed: &[&str], out: &mut Vec<String>) {
    let set = [
        ("empty", m.empty.is_some()),
        ("json", m.json.is_some()),
        ("table", m.table.is_some()),
        ("contains", m.contains.is_some()),
        ("message", m.message.is_some()),
        ("json_error", m.json_error.is_some()),
    ];
    let present: Vec<&str> = set.iter().filter(|(_, p)| *p).map(|(k, _)| *k).collect();
    if present.len() != 1 {
        out.push(format!("`expect.{stream}` takes exactly one of {allowed:?}"));
    }
    for key in present.iter().filter(|k| !allowed.contains(k)) {
        out.push(format!("`expect.{stream}` does not take `{key}`"));
    }
    if !m.vars.is_empty() && m.message.is_none() {
        out.push(format!("`expect.{stream}.vars` needs `message`"));
    }
}

fn check_item(i: usize, item: &Exchange, routes: &Routes, out: &mut Vec<String>) {
    let at = format!("exchanges.items[{i}]");
    check_target(&at, item, routes, out);
    check_delete_body(&at, item, routes, out);
    let r = &item.response;
    if r.redirect.is_some() && (r.json.is_some() || r.text.is_some()) {
        out.push(format!("{at}: a `redirect` response has no body"));
    }
    if r.json.is_some() && r.text.is_some() {
        out.push(format!("{at}: a response is `json` or `text`, not both"));
    }
}

/// `route`, or `request.method` and `request.path`; never both.
fn check_target(at: &str, item: &Exchange, routes: &Routes, out: &mut Vec<String>) {
    let spelled = item.request.method.is_some() || item.request.path.is_some();
    match (&item.route, spelled) {
        (Some(name), false) if routes.get(name).is_none() => out.push(format!("{at}: unknown route `{name}`")),
        (Some(_), true) => out.push(format!("{at}: `route` replaces `request.method` and `request.path`")),
        (None, _) if item.request.method.is_none() || item.request.path.is_none() => {
            out.push(format!("{at}: needs `route`, or `request.method` and `request.path`"));
        }
        _ => {}
    }
    if item.request.path.as_deref().is_some_and(|p| p.contains('?')) {
        out.push(format!("{at}: `request.path` carries no query; use `request.query`"));
    }
}

/// The copy's "a DELETE sends no body", relaxed for destructive routes (B5).
fn check_delete_body(at: &str, item: &Exchange, routes: &Routes, out: &mut Vec<String>) {
    let route_method = item.route.as_deref().and_then(|r| routes.get(r)).map(|r| r.method.as_str());
    let delete = route_method == Some("DELETE") || item.request.method.as_deref() == Some("DELETE");
    let destructive = item.route.as_ref().is_some_and(|r| routes.destructive.contains(r));
    if delete && !destructive && (item.request.json.is_some() || item.request.form.is_some()) {
        out.push(format!("{at}: a DELETE sends no body, except to a destructive route (B5)"));
    }
}
