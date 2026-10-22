//! `lingara`, the Lingara developer CLI: the Rust reference kit
//! (ADR 16.10.26ad D9). Every behaviour a developer or CI job can observe
//! is `conformance/CONTRACT.md`'s; this crate is one way of keeping it.

mod app;
mod assets;
mod auth;
mod canvas;
mod cli;
mod commands;
mod confirm;
mod connection_literal;
mod exit;
mod http;
mod output;
mod platform_toml;
mod store;
mod supervise;
mod terminal;
mod time;
mod tree;
mod tunnel;

#[cfg(test)]
#[path = "fakes_tests.rs"]
mod fakes;

use std::ffi::OsString;
use std::io::Write;

use app::{App, Env};
use http::{Bases, Http, SystemClock, transport::Reqwest};
use store::{Mode, Stores, file::FileStore, keychain::Keychain};

/// `lingara-cli/<semver> (rust; <os>)`, contract B5.
fn user_agent() -> String {
    format!("lingara-cli/{} (rust; {})", env!("CARGO_PKG_VERSION"), std::env::consts::OS)
}

/// `LC_ALL`, else `LC_MESSAGES`, else `LANG`, as BCP 47: `en_US.UTF-8` is
/// `en-US`; `C` and `POSIX` name no language.
fn accept_language(env: &Env) -> Option<String> {
    let raw = ["LC_ALL", "LC_MESSAGES", "LANG"].iter().find_map(|v| env.get(v))?;
    let tag = raw.split(['.', '@']).next().unwrap_or("").replace('_', "-");
    (!tag.is_empty() && tag != "C" && tag != "POSIX").then_some(tag)
}

fn production_app(env: Env) -> Result<App, String> {
    let endpoints = assets::endpoints();
    let config_dir = store::config::config_dir(&env);
    let file = FileStore::new(config_dir.clone().unwrap_or_else(|| ".".into()));
    let stores = Stores { mode: Mode::from_env(env.get("LINGARA_KEYRING")), keychain: Box::new(Keychain), file };
    let portal = env.get("LINGARA_PLATFORM_URL").unwrap_or(&endpoints.platform_url).to_string();
    let http = Http {
        transport: Box::new(Reqwest::new()?),
        clock: Box::new(SystemClock),
        bases: Bases { api: endpoints.api_url.clone(), portal },
        user_agent: user_agent(),
        accept_language: accept_language(&env),
    };
    let no_browser = env.get("LINGARA_NO_BROWSER") == Some("1");
    let browser: Box<dyn Fn(&str) -> bool> = Box::new(move |url| !no_browser && open::that(url).is_ok());
    Ok(App { http, env, stores, terminal: Box::new(terminal::Stdio), browser, endpoints, config_dir, session: None })
}

fn main() {
    let argv: Vec<OsString> = std::env::args_os().skip(1).collect();
    let mut app = match production_app(Env::from_process()) {
        Ok(app) => app,
        Err(e) => {
            eprintln!("error: {e} (failure)");
            std::process::exit(1);
        }
    };
    let outcome = app.run(&argv, Box::new(std::io::stderr()));
    let mut stdout = std::io::stdout();
    let _ = stdout.write_all(&outcome.stdout).and_then(|()| stdout.flush());
    std::process::exit(i32::from(outcome.exit.code()));
}
