//! The exit-code table (ADR 16.10.26ab D3) and each command's `exit_codes`.

use std::collections::BTreeSet;

use super::{is_snake, Refusals, IMPLICIT_EXITS};
use crate::model::{Messages, Spec};
use crate::walk::Walk;

/// The base rows. A row is never renumbered or reused, so all ten are
/// required exactly as written; later ADRs append from 9 to 129.
pub const BASE_EXITS: [(i32, &str); 10] = [
    (0, "ok"),
    (1, "failure"),
    (2, "usage"),
    (3, "auth_required"),
    (4, "not_found"),
    (5, "refused"),
    (6, "not_confirmed"),
    (7, "unavailable"),
    (8, "platform_disabled"),
    (130, "interrupted"),
];

pub fn check(spec: &Spec, walk: &Walk, messages: &Messages, out: &mut Refusals) {
    table(spec, messages, out);
    base_rows(spec, out);
    per_command(spec, walk, out);
}

fn table(spec: &Spec, messages: &Messages, out: &mut Refusals) {
    let ids: BTreeSet<&str> = messages.messages.iter().map(|m| m.id.as_str()).collect();
    let (mut codes, mut names) = (BTreeSet::new(), BTreeSet::new());
    for (i, row) in spec.exit_codes.iter().enumerate() {
        let at = format!("exit_codes[{i}]");
        if !codes.insert(row.code) {
            out.push(&format!("{at}.code"), format!("{} is listed twice", row.code));
        }
        if !names.insert(row.name.as_str()) {
            out.push(&format!("{at}.name"), format!("`{}` is listed twice", row.name));
        }
        if !is_snake(&row.name) {
            out.push(&format!("{at}.name"), format!("`{}` is not snake_case", row.name));
        }
        let base = BASE_EXITS.iter().any(|(c, _)| *c == row.code);
        if !base && !(9..=129).contains(&row.code) {
            out.push(&format!("{at}.code"), format!("{} is outside the appendable 9..=129", row.code));
        }
        if let Some(id) = &row.message
            && !ids.contains(id.as_str())
        {
            out.push(&format!("{at}.message"), format!("`{id}` is not in messages.en.json"));
        }
    }
}

fn base_rows(spec: &Spec, out: &mut Refusals) {
    for (code, name) in BASE_EXITS {
        let row = spec.exit_codes.iter().find(|r| r.code == code);
        match row {
            None => out.push("exit_codes", format!("the base row {code} `{name}` is missing")),
            Some(r) if r.name != name => out.push(
                "exit_codes",
                format!("code {code} is `{name}`, never `{}`: base rows are not renamed", r.name),
            ),
            Some(_) => {}
        }
    }
    let disabled = spec.exit_codes.iter().find(|r| r.name == "platform_disabled");
    if disabled.is_some_and(|r| r.message.as_deref() != Some("platform_disabled")) {
        out.push("exit_codes", "the `platform_disabled` row's message must be `platform_disabled`");
    }
}

fn per_command(spec: &Spec, walk: &Walk, out: &mut Refusals) {
    let known: BTreeSet<&str> = spec.exit_codes.iter().map(|r| r.name.as_str()).collect();
    for site in &walk.commands {
        for (i, name) in site.command.exit_codes.iter().enumerate() {
            let at = format!("{}.exit_codes[{i}]", site.json);
            if IMPLICIT_EXITS.contains(&name.as_str()) {
                out.push(&at, format!("`{name}` is implicit on every command; do not list it"));
            } else if !known.contains(name.as_str()) {
                out.push(&at, format!("`{name}` is not an exit_codes row"));
            }
        }
    }
}
