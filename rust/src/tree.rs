//! `lingara __tree` (ADR 16.10.26ab D4, 16.10.26ad D9): the registered tree,
//! read back from clap's own `Command` model rather than written twice.
//!
//! Types come from clap: `ValueHint::FilePath` is `path`, a `u16` parser is
//! `int`, possible values
//! are an `enum` with its `values`, `SetTrue` is `bool`, `Append` is
//! repeatable. A command is dangerous exactly when it carries the confirm
//! group, which is the schema's own rule (16.10.26ab D2.4).

use clap::{Arg, ArgAction, Command, CommandFactory, ValueHint};
use serde_json::{Value, json};

use crate::cli::Root;

pub fn tree() -> Value {
    let root = Root::command();
    let globals: Vec<Value> = root.get_arguments().filter(|a| is_flag(a)).map(param).collect();
    let mut out = level(&root);
    out["name"] = json!(root.get_name());
    out["global_flags"] = Value::Array(globals);
    out
}

/// A topic's commands and sub-topics: a subcommand with subcommands of its
/// own is a topic.
fn level(cmd: &Command) -> Value {
    let (topics, commands): (Vec<&Command>, Vec<&Command>) =
        cmd.get_subcommands().filter(|c| !c.is_hide_set()).partition(|c| c.has_subcommands());
    let topics: Vec<Value> = topics.into_iter().map(topic).collect();
    let commands: Vec<Value> = commands.into_iter().map(command).collect();
    json!({ "commands": commands, "topics": topics })
}

fn topic(cmd: &Command) -> Value {
    let mut out = level(cmd);
    out["name"] = json!(cmd.get_name());
    out["aliases"] = json!(aliases(cmd));
    out
}

fn command(cmd: &Command) -> Value {
    let own = |a: &&Arg| !a.is_global_set() && a.get_id() != "help";
    let flags: Vec<Value> = cmd.get_arguments().filter(own).filter(|a| is_flag(a)).map(param).collect();
    let args: Vec<Value> = cmd.get_positionals().map(positional).collect();
    let dangerous = cmd.get_arguments().any(|a| a.get_long() == Some("confirm"));
    json!({ "name": cmd.get_name(), "aliases": aliases(cmd), "dangerous": dangerous, "args": args, "flags": flags })
}

fn aliases(cmd: &Command) -> Vec<String> {
    cmd.get_visible_aliases().map(str::to_string).collect()
}

fn is_flag(arg: &Arg) -> bool {
    arg.get_long().is_some() && !matches!(arg.get_action(), ArgAction::Help | ArgAction::Version)
}

fn param(arg: &Arg) -> Value {
    let values: Vec<String> = arg.get_possible_values().iter().map(|v| v.get_name().to_string()).collect();
    let mut out = json!({
        "name": arg.get_long().unwrap_or_default(),
        "type": kind(arg, !values.is_empty()),
        "required": arg.is_required_set(),
        "repeatable": matches!(arg.get_action(), ArgAction::Append),
    });
    if let Some(short) = arg.get_short() {
        out["short"] = json!(short.to_string());
    }
    if !values.is_empty() && !matches!(arg.get_action(), ArgAction::SetTrue) {
        out["values"] = json!(values);
    }
    out
}

fn positional(arg: &Arg) -> Value {
    json!({
        "name": arg.get_id().as_str(),
        "type": kind(arg, false),
        "required": arg.is_required_set(),
        "repeatable": matches!(arg.get_action(), ArgAction::Append),
    })
}

fn kind(arg: &Arg, enumerated: bool) -> &'static str {
    let integer = arg.get_value_parser().type_id() == std::any::TypeId::of::<u16>();
    if matches!(arg.get_action(), ArgAction::SetTrue) {
        "bool"
    } else if integer {
        "int"
    } else if arg.get_value_hint() == ValueHint::FilePath {
        "path"
    } else if enumerated {
        "enum"
    } else {
        "string"
    }
}
