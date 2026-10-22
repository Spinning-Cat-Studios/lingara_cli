//! Topic and command names: snake_case, unique among siblings (aliases
//! included), and clear of the names the frameworks own (D2.3, D2.10).

use std::collections::BTreeSet;

use super::{is_snake, Refusals};
use crate::model::{Command, Spec, Topic};
use crate::walk::Walk;

/// Commands every framework adds on its own, plus the hidden `__tree` (D4).
pub const RESERVED_COMMANDS: [&str; 4] = ["help", "completion", "version", "__tree"];

/// Top-level topics that would land on a fixed reference page once Public
/// slugs `_` to `-` (D5).
pub const RESERVED_TOPICS: [&str; 2] = ["auth", "exit_codes"];

pub fn check(spec: &Spec, walk: &Walk, out: &mut Refusals) {
    siblings("", &spec.commands, &spec.topics, out);
    for site in &walk.topics {
        siblings(&site.json, &site.topic.commands, &site.topic.topics, out);
        spelled(&site.json, &site.topic.name, &site.topic.aliases, out);
    }
    for site in &walk.commands {
        spelled(&site.json, &site.command.name, &site.command.aliases, out);
    }
    for (i, topic) in spec.topics.iter().enumerate() {
        if RESERVED_TOPICS.contains(&topic.name.as_str()) {
            out.push(&format!("topics[{i}].name"), format!("`{}` is reserved for a fixed page", topic.name));
        }
    }
}

/// Snake case and the reserved command names, for one node's name and aliases.
fn spelled(at: &str, name: &str, aliases: &[String], out: &mut Refusals) {
    let all = std::iter::once((format!("{at}.name"), name))
        .chain(aliases.iter().enumerate().map(|(i, a)| (format!("{at}.aliases[{i}]"), a.as_str())));
    for (path, word) in all {
        if RESERVED_COMMANDS.contains(&word) {
            out.push(&path, format!("`{word}` is reserved for the framework"));
        } else if !is_snake(word) {
            out.push(&path, format!("`{word}` is not snake_case"));
        }
    }
}

/// Names and aliases share one namespace among the commands and subtopics
/// of one parent.
fn siblings(at: &str, commands: &[Command], topics: &[Topic], out: &mut Refusals) {
    let prefix = |key: &str, i: usize| {
        if at.is_empty() { format!("{key}[{i}]") } else { format!("{at}.{key}[{i}]") }
    };
    let mut seen = BTreeSet::new();
    let words = commands
        .iter()
        .enumerate()
        .map(|(i, c)| (prefix("commands", i), &c.name, &c.aliases))
        .chain(topics.iter().enumerate().map(|(i, t)| (prefix("topics", i), &t.name, &t.aliases)));
    for (path, name, aliases) in words {
        for word in std::iter::once(name).chain(aliases.iter()) {
            if !seen.insert(word.as_str()) {
                out.push(&path, format!("`{word}` is already a sibling's name or alias"));
            }
        }
    }
}
