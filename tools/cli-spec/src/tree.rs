//! `cli-spec tree-diff` (ADR 16.10.26ab D4): normalise the schema and a
//! kit's `lingara __tree` into one projection, then compare both ways.
//!
//! The projection is a map from a path (`oauth_client delete --confirm`) to
//! the facts compared there. Only the tree is compared: summaries, defaults,
//! `env`, conflicts, output, exit codes, messages and examples are docs or
//! behaviour, which conformance proves.

use std::collections::BTreeMap;

use serde::Deserialize;

use crate::model::{Param, Spec};
use crate::walk::walk;

pub type Facts = BTreeMap<&'static str, String>;
pub type Projection = BTreeMap<String, Facts>;

/// What a framework adds on its own; never a difference (D4).
const BUILTIN_COMMANDS: [&str; 4] = ["help", "completion", "version", "__tree"];

pub fn project_spec(spec: &Spec) -> Projection {
    let mut out = Projection::new();
    for flag in &spec.global_flags {
        insert_flag(&mut out, "", &flag.into());
    }
    let w = walk(spec);
    for site in &w.topics {
        out.insert(site.words.join(" "), node_facts("topic", &site.topic.aliases, None));
    }
    for site in &w.commands {
        let c = site.command;
        let path = site.path();
        out.insert(path.clone(), node_facts("command", &c.aliases, Some(c.dangerous)));
        let groups = c.flag_groups.iter().filter_map(|g| spec.flag_groups.get(g)).flatten();
        for flag in groups.chain(&c.flags) {
            insert_flag(&mut out, &path, &flag.into());
        }
        for (i, arg) in c.args.iter().enumerate() {
            insert_arg(&mut out, &path, i, &arg.into());
        }
    }
    out
}

/// A kit's `__tree`: D2's shape, restricted to the projection. Unknown keys
/// are ignored here (a kit may print more), and an absent boolean is false.
#[derive(Deserialize, Default)]
struct KitNode {
    #[serde(default)]
    name: String,
    #[serde(default)]
    aliases: Vec<String>,
    #[serde(default)]
    dangerous: bool,
    #[serde(default)]
    args: Vec<KitParam>,
    #[serde(default)]
    flags: Vec<KitParam>,
    #[serde(default)]
    global_flags: Vec<KitParam>,
    #[serde(default)]
    commands: Vec<KitNode>,
    #[serde(default)]
    topics: Vec<KitNode>,
}

#[derive(Deserialize)]
struct KitParam {
    name: String,
    #[serde(rename = "type", default)]
    kind: String,
    #[serde(default)]
    short: Option<String>,
    #[serde(default)]
    required: bool,
    #[serde(default)]
    repeatable: bool,
    #[serde(default)]
    values: Vec<String>,
}

impl From<&Param> for KitParam {
    fn from(p: &Param) -> Self {
        KitParam {
            name: p.name.clone(),
            kind: p.kind.as_str().to_string(),
            short: p.short.clone(),
            required: p.required,
            repeatable: p.repeatable,
            values: p.values.clone().unwrap_or_default(),
        }
    }
}

pub fn project_kit(json: &str) -> Result<Projection, String> {
    let root: KitNode = serde_json::from_str(json).map_err(|e| format!("the kit's __tree is not JSON in D2's shape: {e}"))?;
    let mut out = Projection::new();
    for flag in root.global_flags.iter().filter(|f| f.name != "version") {
        insert_kit_flag(&mut out, "", flag);
    }
    kit_level(&mut out, "", &root);
    Ok(out)
}

fn kit_level(out: &mut Projection, at: &str, node: &KitNode) {
    let path = |name: &str| if at.is_empty() { name.to_string() } else { format!("{at} {name}") };
    for c in node.commands.iter().filter(|c| !BUILTIN_COMMANDS.contains(&c.name.as_str())) {
        let p = path(&c.name);
        out.insert(p.clone(), node_facts("command", &c.aliases, Some(c.dangerous)));
        for flag in &c.flags {
            insert_kit_flag(out, &p, flag);
        }
        for (i, arg) in c.args.iter().enumerate() {
            insert_arg(out, &p, i, arg);
        }
    }
    for t in node.topics.iter().filter(|t| !BUILTIN_COMMANDS.contains(&t.name.as_str())) {
        let p = path(&t.name);
        out.insert(p.clone(), node_facts("topic", &t.aliases, None));
        kit_level(out, &p, t);
    }
}

/// `--help`/`-h` are the framework's at every level (D4).
fn insert_kit_flag(out: &mut Projection, at: &str, flag: &KitParam) {
    if flag.name != "help" {
        insert_flag(out, at, flag);
    }
}

fn node_facts(kind: &str, aliases: &[String], dangerous: Option<bool>) -> Facts {
    let mut sorted = aliases.to_vec();
    sorted.sort();
    let mut f = Facts::from([("kind", kind.to_string()), ("aliases", sorted.join(","))]);
    if let Some(d) = dangerous {
        f.insert("dangerous", d.to_string());
    }
    f
}

fn insert_flag(out: &mut Projection, at: &str, p: &KitParam) {
    let key = if at.is_empty() { format!("--{}", p.name) } else { format!("{at} --{}", p.name) };
    let facts = Facts::from([
        ("short", p.short.clone().unwrap_or_default()),
        ("type", p.kind.clone()),
        ("required", p.required.to_string()),
        ("repeatable", p.repeatable.to_string()),
        ("values", p.values.join(",")),
    ]);
    out.insert(key, facts);
}

fn insert_arg(out: &mut Projection, at: &str, i: usize, p: &KitParam) {
    let facts = Facts::from([
        ("name", p.name.clone()),
        ("required", p.required.to_string()),
        ("repeatable", p.repeatable.to_string()),
    ]);
    out.insert(format!("{at} <arg {}>", i + 1), facts);
}

/// Every difference, both directions, each naming its path:
/// `oauth_client delete --confirm: type string ≠ bool` (schema ≠ kit).
pub fn diff(spec: &Projection, kit: &Projection) -> Vec<String> {
    let mut out = Vec::new();
    for (path, want) in spec {
        match kit.get(path) {
            None => out.push(format!("{path}: in the schema, missing from the kit")),
            Some(got) => {
                for (key, w) in want {
                    let g = got.get(key).map(String::as_str).unwrap_or("");
                    if w != g {
                        out.push(format!("{path}: {key} {} ≠ {}", shown(w), shown(g)));
                    }
                }
            }
        }
    }
    for path in kit.keys().filter(|p| !spec.contains_key(*p)) {
        out.push(format!("{path}: in the kit, missing from the schema"));
    }
    out
}

fn shown(s: &str) -> &str {
    if s.is_empty() { "(none)" } else { s }
}
