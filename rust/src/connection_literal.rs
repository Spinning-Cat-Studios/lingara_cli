//! The connection literal subset (ADR 16.10.26ae D2; spec/connection-
//! literal.md): `lingara/connection.js` read without evaluating JavaScript.
//! `lex` makes the tokens, `parse` the statements, `manifest` turns the one
//! `lingara.app` statement's `manifest` into the wire manifest.

mod lex;
mod manifest;
mod parse;

use serde_json::{Map, Value};

use crate::exit::{Exit, Fail};

/// The file's first line, byte for byte.
pub const HEADER: &str = "// lingara connection v1 (schema 2)";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pos {
    pub line: usize,
    pub column: usize,
}

/// Why the file is refused: a code at a position, or a scripted statement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    At(&'static str, Pos),
    Scripted(String),
}

impl Refusal {
    pub fn at(code: &'static str, at: Pos) -> Refusal {
        Refusal::At(code, at)
    }

    /// As B2 prints it: `connection_scripted`, else `usage` with
    /// `<file>:<line>:<column>: <what>`.
    pub fn fail(&self, file: &str) -> Fail {
        match self {
            Refusal::Scripted(handle) => Fail::message(Exit::ConnectionScripted, "connection_scripted", &[("file", file), ("statement", handle)]),
            Refusal::At(code, at) => {
                let what = WHAT.iter().find(|(c, _)| c == code).map_or("not part of the literal subset", |(_, w)| w);
                Fail::new(Exit::Usage, "connection_literal", format!("{file}:{}:{}: {what} ({code})", at.line, at.column))
            }
        }
    }
}

const WHAT: [(&str, &str); 11] = [
    ("header", "the first line must be `// lingara connection v1 (schema 2)`"),
    ("comment", "comments go inside a script; the builder rewrites everything outside them"),
    ("text", "write text in quotes, with only the escapes JSON knows, and no raw tab or line break"),
    ("number", "write finite numbers in decimal, as JSON does"),
    ("key", "write each key once"),
    ("handle", "this handle is already declared"),
    ("reference", "this names no statement declared above it"),
    ("syntax", "not part of the literal subset"),
    ("app_count", "the file needs exactly one lingara.app statement"),
    ("option", "an app takes only client and manifest"),
    ("manifest_key", "not a manifest key lingara reads, or a required one is missing"),
];

#[derive(Debug, Clone, PartialEq)]
pub enum Lit {
    Str(String),
    Num(serde_json::Number),
    Bool(bool),
    Null,
    Arr(Vec<Lit>),
    /// The members, and where the object's `{` is.
    Obj(Vec<Member>, Pos),
    Ref(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Member {
    pub key: String,
    pub at: Pos,
    pub value: Lit,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Statement {
    pub handle: String,
    pub kind: String,
    pub value: Lit,
    /// Where `const` is.
    pub at: Pos,
}

impl Lit {
    /// As JSON; a reference becomes its handle.
    pub fn to_json(&self) -> Value {
        match self {
            Lit::Str(s) | Lit::Ref(s) => Value::String(s.clone()),
            Lit::Num(n) => Value::Number(n.clone()),
            Lit::Bool(b) => Value::Bool(*b),
            Lit::Null => Value::Null,
            Lit::Arr(items) => Value::Array(items.iter().map(Lit::to_json).collect()),
            Lit::Obj(members, _) => Value::Object(members.iter().map(|m| (m.key.clone(), m.value.to_json())).collect::<Map<_, _>>()),
        }
    }
}

/// Every statement in `text`.
pub fn statements(text: &str) -> Result<Vec<Statement>, Refusal> {
    let start = Pos { line: 1, column: 1 };
    let (header, body) = text.split_once('\n').unwrap_or((text, ""));
    if header.trim_end_matches('\r') != HEADER {
        return Err(Refusal::at("header", start));
    }
    let lines = text.lines().count().max(1);
    let end = Pos { line: lines + 1, column: 1 };
    parse::statements(lex::tokens(body, 2)?, end)
}

/// The wire manifest of the file's one `lingara.app` statement.
pub fn app_manifest(text: &str) -> Result<Value, Refusal> {
    let all = statements(text)?;
    let apps: Vec<&Statement> = all.iter().filter(|s| s.kind == "app").collect();
    let app = match apps.as_slice() {
        [one] => *one,
        [] => return Err(Refusal::at("app_count", Pos { line: 1, column: 1 })),
        [_, second, ..] => return Err(Refusal::at("app_count", second.at)),
    };
    let Lit::Obj(options, at) = &app.value else { return Err(Refusal::at("syntax", app.at)) };
    if let Some(stray) = options.iter().find(|m| m.key != "client" && m.key != "manifest") {
        return Err(Refusal::at("option", stray.at));
    }
    match options.iter().find(|m| m.key == "manifest").map(|m| &m.value) {
        Some(Lit::Obj(members, at)) => manifest::convert(members, *at),
        _ => Err(Refusal::at("manifest_key", *at)),
    }
}

#[cfg(test)]
#[path = "connection_literal/literal_tests.rs"]
mod literal_tests;
