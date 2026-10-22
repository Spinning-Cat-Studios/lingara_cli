//! Requests: one function spells a route onto a base, adds contract B5's
//! headers, retries what D6 lets it retry, and hands back the raw answer.
//! Classifying it is `classify`'s; credentials are `auth::session`'s.

pub mod classify;
pub mod routes;
pub mod transport;

use serde_json::Value;

use crate::exit::{Exit, Fail};
use routes::{Base, Route};
use transport::{Transport, WireRequest, WireResponse};

/// At most two retries of an idempotent request, waiting at most this long
/// in all (D6).
const RETRY_BUDGET_SECS: u64 = 30;
const RETRIES: usize = 2;

pub enum Body {
    None,
    Json(Value),
    Form(Vec<(String, String)>),
}

pub enum Auth<'a> {
    None,
    Bearer(&'a str),
    /// RFC 6749 `client_secret_basic`, the CI mint (D3).
    Basic(&'a str, &'a str),
}

pub struct Request<'a> {
    pub route: &'a Route,
    pub vars: &'a [(&'a str, &'a str)],
    pub query: &'a [(&'a str, &'a str)],
    pub body: Body,
}

impl<'a> Request<'a> {
    pub fn new(route: &'a Route) -> Request<'a> {
        Request { route, vars: &[], query: &[], body: Body::None }
    }
}

/// The time a run reads and waits on, so tests never sleep.
pub trait Clock {
    fn now(&self) -> i64;
    fn sleep(&self, secs: u64);
}

pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
    }

    fn sleep(&self, secs: u64) {
        std::thread::sleep(std::time::Duration::from_secs(secs));
    }
}

pub struct Bases {
    pub api: String,
    pub portal: String,
}

pub struct Http {
    pub transport: Box<dyn Transport>,
    pub clock: Box<dyn Clock>,
    pub bases: Bases,
    pub user_agent: String,
    pub accept_language: Option<String>,
}

impl Http {
    /// The route's absolute URL with `vars` filled and `query` appended.
    pub fn url(&self, route: &Route, vars: &[(&str, &str)], query: &[(&str, &str)]) -> String {
        let base = if route.base == Base::Portal { &self.bases.portal } else { &self.bases.api };
        let path = vars.iter().fold(route.path.to_string(), |p, (k, v)| p.replace(&format!("{{{k}}}"), &encode(v)));
        let pairs: Vec<String> = query.iter().map(|(k, v)| format!("{}={}", encode(k), encode(v))).collect();
        let joined = if pairs.is_empty() { String::new() } else { format!("?{}", pairs.join("&")) };
        format!("{}{path}{joined}", base.trim_end_matches('/'))
    }

    fn wire(&self, request: &Request, auth: &Auth) -> WireRequest {
        let mut headers = vec![("user-agent".to_string(), self.user_agent.clone()), ("accept".into(), "application/json".into())];
        if let Some(lang) = &self.accept_language {
            headers.push(("accept-language".into(), lang.clone()));
        }
        match auth {
            Auth::Bearer(token) => headers.push(("authorization".into(), format!("Bearer {token}"))),
            Auth::Basic(id, secret) => headers.push(("authorization".into(), basic(id, secret))),
            Auth::None => {}
        }
        let body = match &request.body {
            Body::None => Vec::new(),
            Body::Json(v) => {
                headers.push(("content-type".into(), "application/json".into()));
                serde_json::to_vec(v).unwrap_or_default()
            }
            Body::Form(pairs) => {
                headers.push(("content-type".into(), "application/x-www-form-urlencoded".into()));
                form(pairs).into_bytes()
            }
        };
        WireRequest { method: request.route.method, url: self.url(request.route, request.vars, request.query), headers, body }
    }

    /// One request, with D6's retries: a `GET`, `PUT`, `PATCH` or `DELETE`
    /// answered 429 or 503 with `Retry-After` is retried up to twice within
    /// 30 s. A `POST` is never retried after a response (B5).
    pub fn send(&self, request: &Request, auth: &Auth) -> Result<WireResponse, Fail> {
        let wire = self.wire(request, auth);
        let mut waited = 0;
        for attempt in 0..=RETRIES {
            let response = self.transport.send(&wire).map_err(|e| unreachable(request.route, &e))?;
            if attempt > 0 && replayed_delete_done(&wire, &response) {
                return Ok(WireResponse { status: 204, headers: Vec::new(), body: Vec::new() });
            }
            match retry_wait(&wire, &response) {
                Some(secs) if attempt < RETRIES && waited + secs <= RETRY_BUDGET_SECS => {
                    self.clock.sleep(secs);
                    waited += secs;
                }
                _ => return Ok(response),
            }
        }
        Err(Fail::failure("unreachable retry loop"))
    }
}

fn unreachable(route: &Route, error: &str) -> Fail {
    Fail::new(Exit::Unavailable, "unavailable", format!("{} could not be reached: {error}", route.name))
}

/// The wait an idempotent request may retry after.
fn retry_wait(wire: &WireRequest, response: &WireResponse) -> Option<u64> {
    let idempotent = matches!(wire.method, "GET" | "PUT" | "PATCH" | "DELETE");
    let busy = matches!(response.status, 429 | 503);
    if !(idempotent && busy) {
        return None;
    }
    response.header("retry-after")?.trim().parse().ok()
}

/// A `DELETE` replayed after it succeeded answers a coded 404 (D6).
fn replayed_delete_done(wire: &WireRequest, response: &WireResponse) -> bool {
    let coded = serde_json::from_slice::<Value>(&response.body).ok().is_some_and(|v| v.get("code").is_some());
    wire.method == "DELETE" && response.status == 404 && coded
}

/// RFC 3986 unreserved characters pass; everything else is `%XX`.
pub fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub fn form(pairs: &[(String, String)]) -> String {
    pairs.iter().map(|(k, v)| format!("{}={}", encode(k), encode(v))).collect::<Vec<_>>().join("&")
}

fn basic(id: &str, secret: &str) -> String {
    use base64::Engine;
    let raw = format!("{}:{}", encode(id), encode(secret));
    format!("Basic {}", base64::engine::general_purpose::STANDARD.encode(raw))
}

#[cfg(test)]
#[path = "http_tests.rs"]
mod http_tests;
