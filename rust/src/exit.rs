//! The exit codes (16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10, rows
//! 9–18) and the one refusal type every command returns, which
//! `output::errors` prints as contract B2's line.

/// A row of `spec/cli.schema.json`'s exit table.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Exit {
    Ok = 0,
    Failure = 1,
    Usage = 2,
    AuthRequired = 3,
    NotFound = 4,
    Refused = 5,
    NotConfirmed = 6,
    Unavailable = 7,
    PlatformDisabled = 8,
    ToolMissing = 9,
    TunnelFailed = 10,
    DevSessionHeld = 11,
    DevSessionLost = 12,
    AppExited = 13,
    ConnectionScripted = 14,
    RenderUrlEphemeral = 15,
    PlatformTomlSecret = 16,
    PlatformTomlSchema = 17,
    AppNotListening = 18,
    Interrupted = 130,
}

impl Exit {
    pub fn code(self) -> u8 {
        self as u8
    }

    /// The row's name, as `apps dev --json` reports how it ended.
    pub fn name(self) -> &'static str {
        const NAMES: [(Exit, &str); 20] = [
            (Exit::Ok, "ok"),
            (Exit::Failure, "failure"),
            (Exit::Usage, "usage"),
            (Exit::AuthRequired, "auth_required"),
            (Exit::NotFound, "not_found"),
            (Exit::Refused, "refused"),
            (Exit::NotConfirmed, "not_confirmed"),
            (Exit::Unavailable, "unavailable"),
            (Exit::PlatformDisabled, "platform_disabled"),
            (Exit::ToolMissing, "tool_missing"),
            (Exit::TunnelFailed, "tunnel_failed"),
            (Exit::DevSessionHeld, "dev_session_held"),
            (Exit::DevSessionLost, "dev_session_lost"),
            (Exit::AppExited, "app_exited"),
            (Exit::ConnectionScripted, "connection_scripted"),
            (Exit::RenderUrlEphemeral, "render_url_ephemeral"),
            (Exit::PlatformTomlSecret, "platform_toml_secret"),
            (Exit::PlatformTomlSchema, "platform_toml_schema"),
            (Exit::AppNotListening, "app_not_listening"),
            (Exit::Interrupted, "interrupted"),
        ];
        NAMES.iter().find(|(e, _)| *e == self).map_or("failure", |(_, n)| n)
    }
}

/// An object a create made before it failed, named on stderr (D2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Made {
    pub kind: String,
    pub id: String,
}

/// Why a command stopped: its exit, the code and text B2 prints, any extra
/// lines of advice, and what a part-way create already made.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fail {
    pub exit: Exit,
    pub code: String,
    pub message: String,
    pub hints: Vec<String>,
    pub made: Vec<Made>,
    /// The refusing answer's body, for a command that re-words it; boxed so
    /// every `Result<_, Fail>` stays small.
    pub body: Option<Box<str>>,
}

impl Fail {
    pub fn new(exit: Exit, code: &str, message: impl Into<String>) -> Fail {
        Fail { exit, code: code.to_string(), message: message.into(), hints: Vec::new(), made: Vec::new(), body: None }
    }

    pub fn hint(mut self, line: impl Into<String>) -> Fail {
        self.hints.push(line.into());
        self
    }

    pub fn usage(message: impl Into<String>) -> Fail {
        Fail::new(Exit::Usage, "usage", message)
    }

    pub fn auth(message: impl Into<String>) -> Fail {
        Fail::new(Exit::AuthRequired, "auth_required", message)
    }

    pub fn failure(message: impl Into<String>) -> Fail {
        Fail::new(Exit::Failure, "failure", message)
    }

    /// A refusal with a `messages.en.json` text: code and message id are the
    /// same word, as every 16.10.26ae row's are.
    pub fn message(exit: Exit, id: &str, vars: &[(&str, &str)]) -> Fail {
        Fail::new(exit, id, crate::assets::message(id, vars))
    }
}
