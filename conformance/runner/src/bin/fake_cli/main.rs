//! `fake-cli`: a tiny, scriptable stand-in for a kit (ADR 16.10.26ac D9).
//!
//! The runner's own tests spawn it as `fake-cli --script <file> <argv…>`.
//! With an empty script it keeps the contract for the commands the
//! committed cases use; each `faults` entry breaks one rule, so a test can
//! prove the matching case bites. It is not a kit and never ships as one.

mod classify;
mod commands;
mod net;
mod ritual;

use std::collections::BTreeSet;
use std::process::ExitCode;

use serde::Deserialize;

/// The D3 exit codes the fake uses.
pub mod exit {
    pub const OK: u8 = 0;
    pub const FAILURE: u8 = 1;
    pub const USAGE: u8 = 2;
    pub const AUTH_REQUIRED: u8 = 3;
    pub const NOT_FOUND: u8 = 4;
    pub const REFUSED: u8 = 5;
    pub const NOT_CONFIRMED: u8 = 6;
    pub const UNAVAILABLE: u8 = 7;
    pub const PLATFORM_DISABLED: u8 = 8;
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Script {
    #[serde(default)]
    pub faults: BTreeSet<String>,
}

impl Script {
    pub fn has(&self, fault: &str) -> bool {
        self.faults.contains(fault)
    }
}

/// A refusal on its way to stderr and the exit code (B2).
#[derive(Debug)]
pub struct Fail {
    pub exit: u8,
    pub code: String,
    pub message: String,
}

impl Fail {
    pub fn new(exit: u8, code: &str, message: impl Into<String>) -> Fail {
        Fail { exit, code: code.to_string(), message: message.into() }
    }
}

/// Everything a command reads: the script, argv and whether `--json`.
pub struct Ctx {
    pub script: Script,
    pub json: bool,
    pub words: Vec<String>,
    pub flags: Vec<(String, Option<String>)>,
}

impl Ctx {
    pub fn flag(&self, name: &str) -> Option<&(String, Option<String>)> {
        self.flags.iter().find(|(n, _)| n == name)
    }
}

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let script = match args.first().map(String::as_str) {
        Some("--script") if args.len() >= 2 => {
            let path = args[1].clone();
            args.drain(..2);
            load_script(&path)
        }
        _ => Script::default(),
    };
    let ctx = parse_argv(script, &args);
    if ctx.json && ctx.script.has("log_to_stdout") {
        println!("lingara: starting");
    }
    match commands::dispatch(&ctx) {
        Ok(()) => ExitCode::from(exit::OK),
        Err(fail) => {
            report(&ctx, &fail);
            ExitCode::from(fail.exit)
        }
    }
}

fn load_script(path: &str) -> Script {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    toml::from_str(&text).unwrap_or_default()
}

/// Words, then `--name[=value]` / `-y`; a value-taking flag takes the next
/// token. The fake knows its value flags by name.
fn parse_argv(script: Script, args: &[String]) -> Ctx {
    const VALUED: &[&str] = &["confirm", "version"];
    let (mut words, mut flags) = (Vec::new(), Vec::new());
    let mut it = args.iter();
    while let Some(a) = it.next() {
        if a == "-y" {
            flags.push(("yes".to_string(), None));
        } else if let Some(name) = a.strip_prefix("--") {
            let (name, value) = match name.split_once('=') {
                Some((n, v)) => (n.to_string(), Some(v.to_string())),
                None if VALUED.contains(&name) => (name.to_string(), it.next().cloned()),
                None => (name.to_string(), None),
            };
            flags.push((name, value));
        } else {
            words.push(a.clone());
        }
    }
    let json = flags.iter().any(|(n, _)| n == "json");
    Ctx { script, json, words, flags }
}

/// B2: `error: <text> (<code>)` on one line, or one JSON line under `--json`.
fn report(ctx: &Ctx, fail: &Fail) {
    if ctx.json {
        let line = serde_json::json!({ "error": { "code": fail.code, "message": fail.message, "exit": fail.exit } });
        eprintln!("{line}");
    } else {
        eprintln!("error: {} ({})", fail.message, fail.code);
    }
}
