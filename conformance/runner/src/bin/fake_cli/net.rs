//! HTTP over a raw socket, the credentials, and B2 rule 2's one refresh.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;

use serde_json::{Value, json};

use crate::classify::classify;
use crate::{Fail, Script, exit};

pub const CLIENT_ID: &str = "lgr_cid_conformance_cli";

pub struct Resp {
    pub status: u16,
    pub body: Vec<u8>,
}

impl Resp {
    pub fn json(&self) -> Option<Value> {
        serde_json::from_slice(&self.body).ok()
    }
}

/// B5's `User-Agent` and `Accept-Language`.
fn identity() -> [(String, String); 2] {
    let locale = ["LC_ALL", "LC_MESSAGES", "LANG"].iter().find_map(|v| std::env::var(v).ok().filter(|s| !s.is_empty()));
    let tag = locale.map(|l| l.split('.').next().unwrap_or("").replace('_', "-")).unwrap_or_default();
    let ua = format!("lingara-cli/0.0.1 (rust; {})", std::env::consts::OS);
    [("user-agent".into(), ua), ("accept-language".into(), tag)]
}

pub fn base() -> String {
    std::env::var("LINGARA_API_URL").unwrap_or_default()
}

/// One request; a connection failure is `unavailable` (B2 rule 7).
pub fn send(method: &str, target: &str, bearer: Option<&str>, body: Option<(&str, Vec<u8>)>) -> Result<Resp, Fail> {
    let base = base();
    let host = base.strip_prefix("http://").unwrap_or(&base).to_string();
    let down = |e: std::io::Error| Fail::new(exit::UNAVAILABLE, "unavailable", format!("{host}: {e}"));
    let mut stream = TcpStream::connect(&host).map_err(down)?;
    let mut head = format!("{method} {target} HTTP/1.1\r\nhost: {host}\r\nconnection: close\r\n");
    for (k, v) in identity() {
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    if let Some(token) = bearer {
        head.push_str(&format!("authorization: Bearer {token}\r\n"));
    }
    let (ctype, bytes) = body.unwrap_or(("", Vec::new()));
    if !bytes.is_empty() {
        head.push_str(&format!("content-type: {ctype}\r\ncontent-length: {}\r\n", bytes.len()));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes()).and_then(|()| stream.write_all(&bytes)).map_err(down)?;
    let mut answer = Vec::new();
    stream.read_to_end(&mut answer).map_err(down)?;
    let split = answer.windows(4).position(|w| w == b"\r\n\r\n").map_or(answer.len(), |p| p + 4);
    let text = String::from_utf8_lossy(&answer[..split]);
    let status = text.split(' ').nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    Ok(Resp { status, body: answer[split..].to_vec() })
}

pub fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub fn form(pairs: &[(&str, &str)]) -> Option<(&'static str, Vec<u8>)> {
    let body: Vec<String> = pairs.iter().map(|(k, v)| format!("{}={}", encode(k), encode(v))).collect();
    Some(("application/x-www-form-urlencoded", body.join("&").into_bytes()))
}

/// `POST /oauth/token`; a refusal is classified as a token-route answer.
pub fn token(script: &Script, pairs: &[(&str, &str)]) -> Result<Value, Fail> {
    let resp = send("POST", "/oauth/token", None, form(pairs))?;
    match resp.status {
        200 => resp.json().ok_or_else(|| Fail::new(exit::FAILURE, "failure", "unexpected response from oauth.token")),
        _ => Err(classify(script, &resp, "oauth.token")),
    }
}

fn credentials_path() -> PathBuf {
    PathBuf::from(std::env::var("LINGARA_CONFIG_DIR").unwrap_or_default()).join("credentials.json")
}

/// ADR 16.10.26ad D4's file: `{schema, profiles: {<profile>: <record>}}`.
pub fn store(tokens: &Value, email: &str) {
    use std::os::unix::fs::PermissionsExt;
    let record = json!({ "email": email, "access_token": tokens["access_token"], "refresh_token": tokens["refresh_token"] });
    let stored = json!({ "schema": 1, "profiles": { "default": record } });
    let path = credentials_path();
    if std::fs::write(&path, stored.to_string()).is_ok() {
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
    }
}

/// The `default` profile's record, if the file holds one.
fn stored_record() -> Option<Value> {
    let file: Value = serde_json::from_slice(&std::fs::read(credentials_path()).ok()?).ok()?;
    file.get("profiles")?.get("default").cloned()
}

/// The signed-in developer: stored credentials, else CI's client.
pub struct Session<'a> {
    pub script: &'a Script,
    creds: Value,
}

impl<'a> Session<'a> {
    /// `auth_required` before any request when there is nothing to sign in with.
    pub fn open(script: &'a Script) -> Result<Session<'a>, Fail> {
        if let Some(creds) = stored_record() {
            return Ok(Session { script, creds });
        }
        let ci = std::env::var("LINGARA_PLATFORM_CLIENT_ID").ok().zip(std::env::var("LINGARA_PLATFORM_CLIENT_SECRET").ok());
        let Some((id, secret)) = ci else { return Err(Fail::new(exit::AUTH_REQUIRED, "auth_required", "not signed in: run `lingara login`")) };
        let tokens = token(script, &[("grant_type", "client_credentials"), ("client_id", &id), ("client_secret", &secret)])?;
        let mut session = Session { script, creds: json!({ "access_token": tokens["access_token"], "ci": [id, secret] }) };
        let me = session.call("GET", "/platform/developer", None, "developer.me")?;
        session.creds["email"] = me["email"].clone();
        Ok(session)
    }

    pub fn email(&self) -> String {
        self.creds["email"].as_str().unwrap_or("").to_string()
    }

    fn refresh(&mut self) -> Result<(), Fail> {
        let tokens = match self.creds["ci"].as_array() {
            Some(ci) => {
                let (id, secret) = (ci[0].as_str().unwrap_or(""), ci[1].as_str().unwrap_or(""));
                token(self.script, &[("grant_type", "client_credentials"), ("client_id", id), ("client_secret", secret)])?
            }
            None => {
                let rt = self.creds["refresh_token"].as_str().unwrap_or("").to_string();
                let tokens = token(self.script, &[("grant_type", "refresh_token"), ("refresh_token", &rt), ("client_id", CLIENT_ID)])?;
                store(&tokens, &self.email());
                tokens
            }
        };
        self.creds["access_token"] = tokens["access_token"].clone();
        Ok(())
    }

    fn raw(&self, method: &str, target: &str, body: Option<(&str, Vec<u8>)>) -> Result<Resp, Fail> {
        send(method, target, self.creds["access_token"].as_str(), body)
    }

    /// B2 rule 2: one refresh and one replay after a `401`, then classify.
    pub fn call(&mut self, method: &str, target: &str, body: Option<Value>, route: &str) -> Result<Value, Fail> {
        let body = || body.as_ref().map(|b| ("application/json", b.to_string().into_bytes()));
        let mut resp = self.raw(method, target, body())?;
        let stale = resp.status == 401 && !self.script.has("no_refresh");
        let empty_404 = resp.status == 404 && resp.body.is_empty() && self.script.has("refresh_on_empty_404");
        if stale || empty_404 {
            self.refresh()?;
            resp = self.raw(method, target, body())?;
            if resp.status == 401 && self.script.has("refresh_twice") {
                self.refresh()?;
            }
        }
        match resp.status {
            200..=299 => Ok(resp.json().unwrap_or(json!({}))),
            _ => Err(classify(self.script, &resp, route)),
        }
    }
}
