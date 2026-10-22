//! The part of `spec/cli.schema.json` and `messages.en.json` a case is
//! judged against (ADR 16.10.26ac D7 rule 1).
//!
//! This reader is deliberately open: `cli-spec check` owns the schema's
//! closed shape, so a key this file does not name is ignored here.

use std::collections::BTreeMap;
use std::path::Path;

use serde::Deserialize;

/// The five exits every command may return (D7 rule 1).
pub const IMPLICIT_EXITS: &[&str] = &["ok", "failure", "usage", "unavailable", "interrupted"];

#[derive(Debug, Default)]
pub struct Schema {
    /// Exit name → code, every row of the D3 table.
    pub exits: BTreeMap<String, i32>,
    pub commands: Vec<SchemaCommand>,
    /// Message id → text.
    pub messages: BTreeMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct SchemaCommand {
    /// The canonical words: `oauth_client delete`.
    pub path: String,
    /// Every spelling, each level by its name or an alias.
    pub spellings: Vec<Vec<String>>,
    pub dangerous: bool,
    pub exit_codes: Vec<String>,
    pub conflicts: Vec<Vec<String>>,
}

impl Schema {
    pub fn command(&self, path: &str) -> Option<&SchemaCommand> {
        self.commands.iter().find(|c| c.path == path)
    }

    /// The command argv's leading positional words spell, if any: `apps list
    /// --nope` resolves to `apps list`.
    pub fn resolve(&self, argv: &[String]) -> Option<&SchemaCommand> {
        let words: Vec<&str> = argv.iter().map(String::as_str).take_while(|w| !w.starts_with('-')).collect();
        let spelled = |s: &Vec<String>| s.len() <= words.len() && s.iter().zip(&words).all(|(a, b)| a == b);
        let longest = |c: &SchemaCommand| c.spellings.iter().filter(|s| spelled(s)).map(Vec::len).max();
        self.commands.iter().filter_map(|c| longest(c).map(|n| (n, c))).max_by_key(|(n, _)| *n).map(|(_, c)| c)
    }

    /// Whether `exit` is one `command` may return.
    pub fn allows_exit(&self, command: &SchemaCommand, exit: &str) -> bool {
        IMPLICIT_EXITS.contains(&exit) || command.exit_codes.iter().any(|e| e == exit)
    }

    /// The exit name for a code, if the D3 table has one.
    pub fn exit_name(&self, code: i32) -> Option<&str> {
        self.exits.iter().find(|(_, c)| **c == code).map(|(n, _)| n.as_str())
    }
}

#[derive(Deserialize)]
struct RawSpec {
    exit_codes: Vec<RawExit>,
    #[serde(default)]
    commands: Vec<RawCommand>,
    #[serde(default)]
    topics: Vec<RawTopic>,
}

#[derive(Deserialize)]
struct RawExit {
    code: i32,
    name: String,
}

#[derive(Deserialize)]
struct RawTopic {
    name: String,
    #[serde(default)]
    aliases: Vec<String>,
    #[serde(default)]
    commands: Vec<RawCommand>,
    #[serde(default)]
    topics: Vec<RawTopic>,
}

#[derive(Deserialize)]
struct RawCommand {
    name: String,
    #[serde(default)]
    aliases: Vec<String>,
    #[serde(default)]
    dangerous: bool,
    #[serde(default)]
    exit_codes: Vec<String>,
    #[serde(default)]
    conflicts: Vec<Vec<String>>,
}

#[derive(Deserialize)]
struct RawMessages {
    messages: Vec<RawMessage>,
}

#[derive(Deserialize)]
struct RawMessage {
    id: String,
    text: String,
}

/// Reads the schema and the `messages.en.json` beside it.
pub fn load(schema_path: &Path) -> Result<Schema, String> {
    let read = |p: &Path| std::fs::read_to_string(p).map_err(|e| format!("{}: {e}", p.display()));
    let spec: RawSpec =
        serde_json::from_str(&read(schema_path)?).map_err(|e| format!("{}: {e}", schema_path.display()))?;
    let messages_path = schema_path.with_file_name("messages.en.json");
    let msgs: RawMessages =
        serde_json::from_str(&read(&messages_path)?).map_err(|e| format!("{}: {e}", messages_path.display()))?;
    let mut schema = Schema {
        exits: spec.exit_codes.into_iter().map(|e| (e.name, e.code)).collect(),
        messages: msgs.messages.into_iter().map(|m| (m.id, m.text)).collect(),
        ..Schema::default()
    };
    let root = (Vec::new(), vec![Vec::new()]);
    visit(&mut schema.commands, &root, &spec.commands, &spec.topics);
    Ok(schema)
}

type Level = (Vec<String>, Vec<Vec<String>>);

fn child(at: &Level, name: &str, aliases: &[String]) -> Level {
    let mut words = at.0.clone();
    words.push(name.to_string());
    let mut spellings = Vec::new();
    for prefix in &at.1 {
        for word in std::iter::once(name).chain(aliases.iter().map(String::as_str)) {
            let mut s = prefix.clone();
            s.push(word.to_string());
            spellings.push(s);
        }
    }
    (words, spellings)
}

fn visit(out: &mut Vec<SchemaCommand>, at: &Level, commands: &[RawCommand], topics: &[RawTopic]) {
    for c in commands {
        let (words, spellings) = child(at, &c.name, &c.aliases);
        out.push(SchemaCommand {
            path: words.join(" "),
            spellings,
            dangerous: c.dangerous,
            exit_codes: c.exit_codes.clone(),
            conflicts: c.conflicts.clone(),
        });
    }
    for t in topics {
        let level = child(at, &t.name, &t.aliases);
        visit(out, &level, &t.commands, &t.topics);
    }
}
