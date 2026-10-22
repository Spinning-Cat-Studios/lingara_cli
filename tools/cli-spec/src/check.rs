//! `cli-spec check`: the rules of ADR 16.10.26ab D2 that the closed types
//! cannot say on their own. Every refusal is `<json path>: <reason>`.

pub mod exits;
mod flags;
mod messages;
mod names;
mod text;

use crate::model::{Messages, Spec};
use crate::walk::walk;

/// The five codes every command may return, so no command lists them (D2.7).
pub const IMPLICIT_EXITS: [&str; 5] = ["ok", "failure", "usage", "unavailable", "interrupted"];

/// The ritual's and the disabled answer's ids, required by name (D2.8).
pub const REQUIRED_MESSAGES: [&str; 6] = [
    "confirm_yes_no",
    "confirm_type_email",
    "confirm_declined",
    "confirm_email_mismatch",
    "confirm_non_tty_refused",
    "platform_disabled",
];

/// The shape version this tool reads. A change to the shape bumps it (D2.11).
pub const SCHEMA_VERSION: u32 = 1;

/// Refusals collected over one run, each already prefixed with its path.
#[derive(Default)]
pub struct Refusals(pub Vec<String>);

impl Refusals {
    pub fn push(&mut self, path: &str, reason: impl AsRef<str>) {
        let path = if path.is_empty() { "$" } else { path };
        self.0.push(format!("{path}: {}", reason.as_ref()));
    }
}

/// Every refusal for `spec` read beside `messages`; empty means valid.
pub fn check(spec: &Spec, messages: &Messages) -> Vec<String> {
    let mut out = Refusals::default();
    if spec.schema != SCHEMA_VERSION {
        out.push("schema", format!("is {}, this tool reads {SCHEMA_VERSION}", spec.schema));
    }
    if spec.binary.is_empty() {
        out.push("binary", "is empty");
    }
    let walk = walk(spec);
    exits::check(spec, &walk, messages, &mut out);
    names::check(spec, &walk, &mut out);
    flags::check(spec, &walk, &mut out);
    messages::check(spec, &walk, messages, &mut out);
    text::check(spec, &walk, &mut out);
    out.0
}

pub fn is_snake(s: &str) -> bool {
    let mut parts = s.split('_');
    let first_ok = parts.next().is_some_and(|p| p.starts_with(|c: char| c.is_ascii_lowercase()) && is_word(p));
    first_ok && parts.all(|p| !p.is_empty() && is_word(p))
}

pub fn is_kebab(s: &str) -> bool {
    let mut parts = s.split('-');
    let first_ok = parts.next().is_some_and(|p| p.starts_with(|c: char| c.is_ascii_lowercase()) && is_word(p));
    first_ok && parts.all(|p| !p.is_empty() && is_word(p))
}

fn is_word(p: &str) -> bool {
    p.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
}
