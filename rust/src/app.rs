//! One run: parse argv, pick the profile and the API, dispatch, and turn the
//! result into an exit code. `run` is the one entry point, used by `main`
//! and, in-process, by canvas apply's inner verbs (D7 step 4), which share
//! this `App`'s signed-in session, so no second token exchange happens.

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::io::Write;
use std::path::PathBuf;

use clap::Parser;
use clap::error::ErrorKind;

use crate::assets::Endpoints;
use crate::auth::session::Session;
use crate::cli::Root;
use crate::exit::{Exit, Fail};
use crate::http::Http;
use crate::output::{Output, errors};
use crate::store::Stores;
use crate::store::config::profile_config;
use crate::terminal::Terminal;

/// The process environment, read once, so tests pass their own.
#[derive(Debug, Clone, Default)]
pub struct Env(pub BTreeMap<String, String>);

impl Env {
    pub fn from_process() -> Env {
        Env(std::env::vars().collect())
    }

    /// A set, non-empty variable.
    pub fn get(&self, name: &str) -> Option<&str> {
        self.0.get(name).map(String::as_str).filter(|v| !v.is_empty())
    }
}

pub struct App {
    pub http: Http,
    pub env: Env,
    pub stores: Stores,
    pub terminal: Box<dyn Terminal>,
    /// Opens a URL in the browser; `false` when it could not.
    pub browser: Box<dyn Fn(&str) -> bool>,
    pub endpoints: Endpoints,
    pub config_dir: Option<PathBuf>,
    pub session: Option<Session>,
}

/// What one command sees: the app, its streams and the profile.
pub struct Ctx<'a> {
    pub app: &'a mut App,
    pub out: &'a mut Output,
    pub profile: String,
    /// `--profile` or `LINGARA_PROFILE` chose it, so `platform.toml`'s
    /// `[platform] profile` may not (16.10.26ae D4).
    pub profile_chosen: bool,
}

pub struct Outcome {
    pub exit: Exit,
    pub stdout: Vec<u8>,
    pub fail: Option<Fail>,
}

impl App {
    /// Runs `argv` (without the binary's name), writing stderr to `err`.
    pub fn run(&mut self, argv: &[OsString], err: Box<dyn Write>) -> Outcome {
        let json_flag = argv.iter().any(|a| a == "--json");
        let root = match Root::try_parse_from(std::iter::once(OsString::from("lingara")).chain(argv.iter().cloned())) {
            Ok(root) => root,
            Err(e) => return parse_failure(&e, json_flag, err),
        };
        let profile = root.profile.clone().or_else(|| self.env.get("LINGARA_PROFILE").map(str::to_string));
        let profile_chosen = profile.is_some();
        let profile = profile.unwrap_or_else(|| "default".into());
        let config = profile_config(self.config_dir.as_deref(), &profile);
        self.http.bases.api = self.api_base(config.api_url.as_deref());
        let json = root.json || config.output.as_deref() == Some("json");
        let mut out = Output::new(err, json);
        let result = {
            let mut ctx = Ctx { app: self, out: &mut out, profile, profile_chosen };
            crate::commands::dispatch(&mut ctx, root.command)
        };
        finish(out, result)
    }

    /// D3's order: `LINGARA_API_URL`, then `platform.toml`'s `[platform]
    /// api`, then the profile's `api_url`, then `endpoints.json`'s. The
    /// `apps` commands that read a `platform.toml` apply its slot when they
    /// open the file (16.10.26ae D4, `commands::apps::rows`); everything else
    /// never reads one.
    fn api_base(&self, profile_api: Option<&str>) -> String {
        let chosen = self.env.get("LINGARA_API_URL").or(profile_api);
        chosen.unwrap_or(&self.endpoints.api_url).to_string()
    }
}

fn finish(mut out: Output, result: Result<(), Fail>) -> Outcome {
    match result {
        Ok(()) => Outcome { exit: Exit::Ok, stdout: out.stdout, fail: None },
        Err(fail) => {
            for line in errors::lines(&fail, out.json) {
                out.note(&line);
            }
            Outcome { exit: fail.exit, stdout: out.stdout, fail: Some(fail) }
        }
    }
}

/// clap's answer: `--help` and `--version` print to stdout and exit 0;
/// anything else is `usage` (2), and reaches no server.
fn parse_failure(e: &clap::Error, json: bool, err: Box<dyn Write>) -> Outcome {
    if matches!(e.kind(), ErrorKind::DisplayHelp | ErrorKind::DisplayVersion) {
        return Outcome { exit: Exit::Ok, stdout: e.to_string().into_bytes(), fail: None };
    }
    let rendered = e.to_string();
    let first = rendered.lines().next().unwrap_or("").trim_start_matches("error: ").to_string();
    let mut fail = Fail::usage(first);
    if !json {
        fail.hints = rendered.lines().skip(1).filter(|l| !l.trim().is_empty()).map(str::to_string).collect();
    }
    finish(Output::new(err, json), Err(fail))
}

#[cfg(test)]
mod tests {
    use crate::fakes::{Script, app, pipe};

    /// 16.10.26ad AC19: `lingara <VERSION> (rust)` and nothing else on stdout.
    #[test]
    fn version_names_the_kit() {
        let mut a = app(&Script::default(), pipe());
        let outcome = a.run(&["--version".into()], Box::new(std::io::sink()));
        let want = format!("lingara {} (rust)\n", include_str!("../../VERSION").trim());
        assert_eq!(String::from_utf8_lossy(&outcome.stdout), want);
        assert_eq!(outcome.exit, crate::exit::Exit::Ok);
    }
}
