//! One depth-first walk over the tree, in schema order, shared by `check`,
//! `docs` and `tree-diff` so the three never disagree about what a path is.

use crate::model::{Command, Spec, Topic};

pub struct TopicSite<'a> {
    /// The JSON path, as `check` names it: `topics[3].topics[0]`.
    pub json: String,
    /// The canonical words: `["webhook", "inbound"]`.
    pub words: Vec<String>,
    pub topic: &'a Topic,
}

pub struct CommandSite<'a> {
    pub json: String,
    pub words: Vec<String>,
    /// Every way to spell the path, the canonical one first: each level by
    /// its name or any of its aliases.
    pub spellings: Vec<Vec<String>>,
    pub command: &'a Command,
}

impl CommandSite<'_> {
    pub fn path(&self) -> String {
        self.words.join(" ")
    }
}

#[derive(Default)]
pub struct Walk<'a> {
    pub topics: Vec<TopicSite<'a>>,
    pub commands: Vec<CommandSite<'a>>,
}

pub fn walk(spec: &Spec) -> Walk<'_> {
    let mut out = Walk::default();
    let root = Level { json: String::new(), words: Vec::new(), spellings: vec![Vec::new()] };
    visit_commands(&mut out, &root, &spec.commands);
    visit_topics(&mut out, &root, &spec.topics);
    out
}

struct Level {
    json: String,
    words: Vec<String>,
    spellings: Vec<Vec<String>>,
}

impl Level {
    fn child(&self, json: String, name: &str, aliases: &[String]) -> Level {
        let mut words = self.words.clone();
        words.push(name.to_string());
        let mut spellings = Vec::new();
        for prefix in &self.spellings {
            for word in std::iter::once(name).chain(aliases.iter().map(String::as_str)) {
                let mut s = prefix.clone();
                s.push(word.to_string());
                spellings.push(s);
            }
        }
        Level { json, words, spellings }
    }
}

fn join_json(prefix: &str, key: &str, i: usize) -> String {
    if prefix.is_empty() { format!("{key}[{i}]") } else { format!("{prefix}.{key}[{i}]") }
}

fn visit_commands<'a>(out: &mut Walk<'a>, at: &Level, commands: &'a [Command]) {
    for (i, command) in commands.iter().enumerate() {
        let level = at.child(join_json(&at.json, "commands", i), &command.name, &command.aliases);
        out.commands.push(CommandSite {
            json: level.json,
            words: level.words,
            spellings: level.spellings,
            command,
        });
    }
}

fn visit_topics<'a>(out: &mut Walk<'a>, at: &Level, topics: &'a [Topic]) {
    for (i, topic) in topics.iter().enumerate() {
        let level = at.child(join_json(&at.json, "topics", i), &topic.name, &topic.aliases);
        out.topics.push(TopicSite { json: level.json.clone(), words: level.words.clone(), topic });
        visit_commands(out, &level, &topic.commands);
        visit_topics(out, &level, &topic.topics);
    }
}
