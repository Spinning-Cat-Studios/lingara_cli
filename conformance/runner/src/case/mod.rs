//! The case format (ADR 16.10.26ac D3).
//!
//! The schema is closed: every struct denies unknown fields, so a typo in a
//! case fails `check-coverage` rather than a kit. A new key is an ADR's
//! change to this file. The `exchanges` block is the API clients' (ADR
//! 29.9.26n D10), plus `route`, `vars`, `redirect` and `min_gap_ms`.

pub mod apps;
pub mod check;
mod exchange;
mod load;
pub mod schema;

pub use exchange::{Exchange, Exchanges, HeaderMatch, Order, Response};
#[cfg(test)]
pub use load::parse;
pub use load::{Loaded, load_dir};

use std::collections::BTreeMap;

use serde::Deserialize;
use serde_json::Value;

/// The groups a case may live in; the directory name is the group.
pub const GROUPS: &[&str] = &["b1", "b2", "b3", "b4", "b5", "b6", "b7", "op", "apps"];

/// The developer every fixture signs in as (D3).
pub const DEV_EMAIL: &str = "dev@conformance.invalid";

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize)]
pub enum Behaviour {
    B1,
    B2,
    B3,
    B4,
    B5,
    B6,
    B7,
}

pub const BEHAVIOURS: [Behaviour; 7] =
    [Behaviour::B1, Behaviour::B2, Behaviour::B3, Behaviour::B4, Behaviour::B5, Behaviour::B6, Behaviour::B7];

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Case {
    pub id: String,
    pub title: String,
    pub behaviours: Vec<Behaviour>,
    /// The canonical schema path (`oauth_client delete`); absent only when
    /// `argv` resolves to no schema command.
    pub command: Option<String>,
    pub confirm: Option<Confirm>,
    pub argv: Vec<String>,
    #[serde(default)]
    pub tty: bool,
    /// Non-tty only: written, then closed.
    pub stdin: Option<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    #[serde(default)]
    pub credentials: Credentials,
    #[serde(default)]
    pub interact: Vec<Interact>,
    pub expect: Expect,
    pub exchanges: Option<Exchanges>,
    // ADR 16.10.26ae D11: the `apps` group's keys, refused elsewhere.
    pub mock: Option<apps::MockKind>,
    pub mock_seed: Option<apps::MockSeed>,
    pub project: Option<apps::Project>,
    pub tunnel: Option<apps::TunnelSpec>,
    pub signal: Option<apps::Signal>,
    pub exit_timeout_secs: Option<u64>,
}

impl Case {
    /// Whether argv asks for machine output (I4).
    pub fn json_output(&self) -> bool {
        self.argv.iter().any(|a| a == "--json")
    }

    /// The group, from the id's `<group>.` prefix.
    pub fn group(&self) -> &str {
        self.id.split('.').next().unwrap_or("")
    }

    /// The D11 keys this case uses, by name: valid in the `apps` group only.
    pub fn apps_keys(&self) -> Vec<&'static str> {
        let used = [
            ("mock", self.mock.is_some()),
            ("mock_seed", self.mock_seed.is_some()),
            ("project", self.project.is_some()),
            ("tunnel", self.tunnel.is_some()),
            ("signal", self.signal.is_some()),
            ("exit_timeout_secs", self.exit_timeout_secs.is_some()),
            ("expect.calls", self.expect.calls.is_some()),
            ("expect.child_env", self.expect.child_env.is_some()),
            ("expect.files", self.expect.files.is_some()),
        ];
        used.iter().filter(|(_, u)| *u).map(|(k, _)| *k).collect()
    }

    /// How many `interact` steps write to the terminal.
    pub fn sends(&self) -> usize {
        self.interact.iter().filter(|i| i.send.is_some()).count()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Confirm {
    TtyOk,
    TtyDeclined,
    TtyMismatch,
    NonTtyRefused,
    NonTtyFlags,
    NonTtyFlagsMismatch,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Credentials {
    #[default]
    None,
    Stored,
    Ci,
}

/// One step: wait for `expect` on the terminal and write `send`, or act as
/// the browser.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Interact {
    pub expect: Option<TextMatch>,
    pub send: Option<String>,
    pub browser: Option<Browser>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Browser {
    /// GET the first http(s) URL on stderr and follow its redirects.
    FollowUrl,
}

/// A substring of the terminal: a `messages.en.json` id rendered with
/// `vars` (an unfilled `{placeholder}` matches anything), or literal text.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TextMatch {
    pub message: Option<String>,
    #[serde(default)]
    pub vars: BTreeMap<String, String>,
    pub contains: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Expect {
    /// A D3 exit name, never a number.
    pub exit: String,
    pub stdout: Option<StreamMatch>,
    pub stderr: Option<StreamMatch>,
    #[serde(default)]
    pub redacted: Vec<String>,
    pub stored_credentials: Option<Presence>,
    pub calls: Option<Vec<apps::CallMatch>>,
    pub child_env: Option<apps::ChildEnv>,
    pub files: Option<apps::Files>,
}

/// What one stream holds. Exactly one key; `check` refuses a stdout key on
/// stderr and the reverse.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StreamMatch {
    pub empty: Option<bool>,
    pub json: Option<Value>,
    pub table: Option<Table>,
    pub contains: Option<Vec<String>>,
    pub message: Option<String>,
    #[serde(default)]
    pub vars: BTreeMap<String, String>,
    pub json_error: Option<BTreeMap<String, Value>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Table {
    pub columns: Vec<String>,
    pub rows: Vec<Vec<String>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Presence {
    Present,
    Absent,
}
