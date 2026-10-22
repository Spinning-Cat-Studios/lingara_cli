//! The unit tests' world: a scripted transport, a terminal, a clock that
//! never sleeps and an in-memory keychain holding the conformance fixture's
//! developer. Every seam `App` has, faked once.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::io::Write;
use std::rc::Rc;

use serde_json::Value;

use crate::app::{App, Ctx, Env};
use crate::assets;
use crate::http::transport::{Transport, WireRequest, WireResponse};
use crate::http::{Bases, Clock, Http};
use crate::output::Output;
use crate::store::memory::MemoryStore;
use crate::store::{Mode, ProfileRecord, Stores, file::FileStore};
use crate::terminal::Terminal;

pub const EMAIL: &str = "dev@conformance.invalid";

/// Requests answered in order; each answer names the `METHOD /path` it
/// expects, and anything else is a `599`, as the conformance mock does.
#[derive(Clone, Default)]
pub struct Script {
    pub answers: Rc<RefCell<VecDeque<(String, WireResponse)>>>,
    pub log: Rc<RefCell<Vec<WireRequest>>>,
}

impl Script {
    pub fn then(&self, expect: &str, status: u16, body: Value) -> &Script {
        let body = if body.is_null() { Vec::new() } else { serde_json::to_vec(&body).unwrap() };
        self.answers.borrow_mut().push_back((expect.into(), WireResponse { status, headers: Vec::new(), body }));
        self
    }

    pub fn sent(&self) -> Vec<String> {
        self.log.borrow().iter().map(|r| format!("{} {}", r.method, path_of(&r.url))).collect()
    }

    pub fn body(&self, i: usize) -> Value {
        serde_json::from_slice(&self.log.borrow()[i].body).unwrap_or(Value::Null)
    }
}

/// The path and query of an absolute URL.
fn path_of(url: &str) -> String {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    rest.find('/').map_or("/".into(), |i| rest[i..].to_string())
}

impl Transport for Script {
    fn send(&self, request: &WireRequest) -> Result<WireResponse, String> {
        self.log.borrow_mut().push(request.clone());
        let got = format!("{} {}", request.method, path_of(&request.url));
        match self.answers.borrow_mut().pop_front() {
            Some((want, response)) if got == want || got.split('?').next() == Some(want.as_str()) => Ok(response),
            Some((want, _)) => Ok(WireResponse { status: 599, headers: Vec::new(), body: format!("expected {want}").into_bytes() }),
            None => Err("connection reset".into()),
        }
    }
}

pub struct FakeTerminal {
    pub interactive: bool,
    pub lines: VecDeque<String>,
}

impl Terminal for FakeTerminal {
    fn interactive(&self) -> bool {
        self.interactive
    }

    fn read_line(&mut self) -> Option<String> {
        self.lines.pop_front()
    }
}

pub struct FakeClock;

impl Clock for FakeClock {
    fn now(&self) -> i64 {
        1_760_000_000
    }

    fn sleep(&self, _secs: u64) {}
}

/// stderr, kept for the test to read.
#[derive(Clone, Default)]
pub struct Shared(pub Rc<RefCell<Vec<u8>>>);

impl Shared {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.0.borrow()).into_owned()
    }
}

impl Write for Shared {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.borrow_mut().extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub fn stored_record() -> ProfileRecord {
    ProfileRecord {
        developer_id: "0b9c4f0e-0000-4000-8000-0000000000de".into(),
        email: EMAIL.into(),
        scopes: crate::auth::SCOPES.into(),
        refresh_token: "lgr_rt_conformance_stored".into(),
        access_token: "lgr_at_conformance_stored".into(),
        access_expires_at: "2099-01-01T00:00:00Z".into(),
    }
}

/// An app signed in as the fixture developer, over `script`.
pub fn app(script: &Script, terminal: FakeTerminal) -> App {
    let keychain = MemoryStore::default();
    keychain.records.borrow_mut().insert("default".into(), stored_record());
    let http = Http {
        transport: Box::new(script.clone()),
        clock: Box::new(FakeClock),
        bases: Bases { api: "http://mock".into(), portal: "http://mock".into() },
        user_agent: "lingara-cli/0.0.0 (rust; test)".into(),
        accept_language: Some("en-US".into()),
    };
    App {
        http,
        env: Env::default(),
        stores: Stores { mode: Mode::Keychain, keychain: Box::new(keychain), file: FileStore::new("/nonexistent".into()) },
        terminal: Box::new(terminal),
        browser: Box::new(|_| false),
        endpoints: assets::endpoints(),
        config_dir: None,
        session: None,
    }
}

pub fn pipe() -> FakeTerminal {
    FakeTerminal { interactive: false, lines: VecDeque::new() }
}

pub fn tty(lines: &[&str]) -> FakeTerminal {
    FakeTerminal { interactive: true, lines: lines.iter().map(|l| format!("{l}\n")).collect() }
}

/// Runs `f` against a fresh context over `app`, with `--json` as asked.
pub fn with_ctx<T>(app: &mut App, json: bool, f: impl FnOnce(&mut Ctx) -> T) -> (T, String, String) {
    let err = Shared::default();
    let mut out = Output::new(Box::new(err.clone()), json);
    let result = f(&mut Ctx { app, out: &mut out, profile: "default".into(), profile_chosen: false });
    (result, String::from_utf8_lossy(&out.stdout).into_owned(), err.text())
}
