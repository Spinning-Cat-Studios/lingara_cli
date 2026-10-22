//! `spec/messages.en.json` against the schema (D2.8): the required ids are
//! present, every id a command or exit row names exists, and no id is in
//! the file for show.

use std::collections::BTreeSet;

use super::{is_snake, Refusals, REQUIRED_MESSAGES, SCHEMA_VERSION};
use crate::model::{Messages, Spec};
use crate::walk::Walk;

pub fn check(spec: &Spec, walk: &Walk, messages: &Messages, out: &mut Refusals) {
    if messages.schema != SCHEMA_VERSION {
        out.push("messages.schema", format!("is {}, this tool reads {SCHEMA_VERSION}", messages.schema));
    }
    let ids = file_ids(messages, out);
    for id in REQUIRED_MESSAGES.iter().filter(|id| !ids.contains(*id)) {
        out.push("messages", format!("the required id `{id}` is missing"));
    }
    let mut named: BTreeSet<&str> = REQUIRED_MESSAGES.into_iter().collect();
    named.extend(spec.exit_codes.iter().filter_map(|r| r.message.as_deref()));
    for site in &walk.commands {
        for (i, id) in site.command.messages.iter().enumerate() {
            if !ids.contains(id.as_str()) {
                out.push(&format!("{}.messages[{i}]", site.json), format!("`{id}` is not in messages.en.json"));
            }
            named.insert(id);
        }
    }
    for (i, m) in messages.messages.iter().enumerate() {
        if !named.contains(m.id.as_str()) {
            out.push(&format!("messages[{i}].id"), format!("`{}` is named by no command or exit row", m.id));
        }
    }
}

fn file_ids<'a>(messages: &'a Messages, out: &mut Refusals) -> BTreeSet<&'a str> {
    let mut ids = BTreeSet::new();
    for (i, m) in messages.messages.iter().enumerate() {
        let at = format!("messages[{i}].id");
        if !is_snake(&m.id) {
            out.push(&at, format!("`{}` is not snake_case", m.id));
        }
        if !ids.insert(m.id.as_str()) {
            out.push(&at, format!("`{}` is listed twice", m.id));
        }
        if m.text.trim().is_empty() {
            out.push(&format!("messages[{i}].text"), "is empty: a kit prints it byte for byte");
        }
    }
    ids
}
