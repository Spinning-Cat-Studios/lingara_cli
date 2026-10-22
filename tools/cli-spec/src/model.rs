//! The closed shape of `spec/cli.schema.json` and `spec/messages.en.json`
//! (ADR 16.10.26ab D2). Every struct refuses a key it does not name, so
//! adding a key to the shape is a change here, and an ADR.

use std::collections::BTreeMap;

use serde::de::DeserializeOwned;
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Spec {
    pub schema: u32,
    pub binary: String,
    pub exit_codes: Vec<ExitCode>,
    #[serde(default)]
    pub global_flags: Vec<Param>,
    #[serde(default)]
    pub flag_groups: BTreeMap<String, Vec<Param>>,
    #[serde(default)]
    pub commands: Vec<Command>,
    #[serde(default)]
    pub topics: Vec<Topic>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Topic {
    pub name: String,
    #[serde(default)]
    pub aliases: Vec<String>,
    pub summary: Option<String>,
    #[serde(default)]
    pub commands: Vec<Command>,
    #[serde(default)]
    pub topics: Vec<Topic>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Command {
    pub name: String,
    #[serde(default)]
    pub aliases: Vec<String>,
    pub summary: Option<String>,
    #[serde(default)]
    pub args: Vec<Param>,
    #[serde(default)]
    pub flags: Vec<Param>,
    #[serde(default)]
    pub flag_groups: Vec<String>,
    #[serde(default)]
    pub conflicts: Vec<Vec<String>>,
    #[serde(default)]
    pub dangerous: bool,
    pub output: Option<Output>,
    #[serde(default)]
    pub exit_codes: Vec<String>,
    #[serde(default)]
    pub messages: Vec<String>,
    #[serde(default)]
    pub examples: Vec<Example>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExitCode {
    pub code: i32,
    pub name: String,
    pub summary: String,
    pub message: Option<String>,
}

/// An arg or a flag. `short` is legal on flags only, which `check` enforces.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Param {
    pub name: String,
    #[serde(rename = "type")]
    pub kind: ParamType,
    #[serde(default)]
    pub required: bool,
    #[serde(default)]
    pub repeatable: bool,
    pub default: Option<serde_json::Value>,
    pub values: Option<Vec<String>>,
    pub value_name: Option<String>,
    pub env: Option<String>,
    pub short: Option<String>,
    pub summary: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ParamType {
    String,
    Bool,
    Int,
    Path,
    Enum,
}

impl ParamType {
    pub fn as_str(self) -> &'static str {
        match self {
            ParamType::String => "string",
            ParamType::Bool => "bool",
            ParamType::Int => "int",
            ParamType::Path => "path",
            ParamType::Enum => "enum",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Output {
    pub columns: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Example {
    pub argv: String,
    pub summary: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Messages {
    pub schema: u32,
    pub messages: Vec<Message>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Message {
    pub id: String,
    pub text: String,
}

/// Parses `text` as `T`, naming the JSON path of the first refusal
/// (`topics[1].commands[0].nope: unknown field ...`).
pub fn parse<T: DeserializeOwned>(text: &str) -> Result<T, String> {
    let de = &mut serde_json::Deserializer::from_str(text);
    serde_path_to_error::deserialize(de).map_err(|e| format!("{}: {}", e.path(), e.inner()))
}
