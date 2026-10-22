//! The signed-in session and every authenticated call (D3, contract B2
//! rule 2): a `401` to a bearer request refreshes once (a fresh
//! `client_credentials` mint under CI) and replays once; a second `401` is
//! `auth_required`, and any other answer to the replay is classified again.

use serde_json::{Value, json};

use super::hints::{PORTAL_LINE, advise};
use super::tokens::Tokens;
use super::{REFRESH_SKEW_SECS, SCOPES};
use crate::app::Ctx;
use crate::exit::Fail;
use crate::http::classify::{Answer, Verdict, classify, fail_for};
use crate::http::routes::{Fixed, Route, fixed};
use crate::http::transport::WireResponse;
use crate::http::{Auth, Body, Request};
use crate::store::ProfileRecord;
use crate::time;

pub enum Cred {
    /// `LINGARA_PLATFORM_CLIENT_ID` / `_SECRET`: memory only, nothing stored.
    Ci { id: String, secret: String },
    Stored { record: ProfileRecord },
}

pub struct Session {
    pub cred: Cred,
    pub access: String,
    /// The email `--confirm` is checked against: the record's, or under CI
    /// `developer.me`'s, read once (D5 step 2).
    pub email: Option<String>,
    pub scopes: String,
}

/// A 2xx body as JSON; an empty one is `{}`.
fn body_json(reply: &WireResponse, route: &Route) -> Result<Value, Fail> {
    if reply.body.iter().all(u8::is_ascii_whitespace) {
        return Ok(json!({}));
    }
    serde_json::from_slice(&reply.body).map_err(|_| Fail::failure(format!("unexpected response from {}", route.name)))
}

fn answer<'a>(route: &'a Route, reply: &'a WireResponse, bearer: bool) -> Answer<'a> {
    let retry_after = reply.header("retry-after").and_then(|v| v.trim().parse().ok());
    Answer {
        route: &route.name,
        token_route: route.is_token(),
        platform_route: route.is_platform(),
        bearer,
        status: reply.status,
        body: &reply.body,
        retry_after,
    }
}

/// The refusal for a non-2xx reply that is not rule 2's.
pub fn refusal(route: &Route, reply: &WireResponse, bearer: bool) -> Fail {
    let a = answer(route, reply, bearer);
    let exit = match classify(&a) {
        Verdict::Exit(exit) => exit,
        Verdict::Refresh => crate::exit::Exit::AuthRequired,
    };
    let mut fail = advise(fail_for(&a, exit));
    let lingara_code = serde_json::from_slice::<Value>(&reply.body).ok().and_then(|v| v["lingara_code"].as_str().map(str::to_string));
    if lingara_code.as_deref() == Some("developer_account_required") && !fail.hints.iter().any(|h| h == PORTAL_LINE) {
        fail.hints.push(PORTAL_LINE.into());
    }
    fail
}

impl Ctx<'_> {
    /// The CI pair, when both halves are set (D3 rule 1).
    pub fn ci_pair(&self) -> Option<(String, String)> {
        let id = self.app.env.get("LINGARA_PLATFORM_CLIENT_ID")?;
        let secret = self.app.env.get("LINGARA_PLATFORM_CLIENT_SECRET")?;
        Some((id.to_string(), secret.to_string()))
    }

    /// A token-endpoint form `POST`: no bearer, classified as a token route.
    pub fn token_post(&mut self, route: Fixed, form: Vec<(String, String)>, auth: &Auth) -> Result<Value, Fail> {
        let route = fixed(route);
        let mut request = Request::new(&route);
        request.body = Body::Form(form);
        let reply = self.app.http.send(&request, auth)?;
        if (200..300).contains(&reply.status) { body_json(&reply, &route) } else { Err(refusal(&route, &reply, false)) }
    }

    fn mint(&mut self, id: &str, secret: &str) -> Result<Tokens, Fail> {
        let form = vec![("grant_type".into(), "client_credentials".into()), ("scope".into(), SCOPES.into())];
        self.token_post(Fixed::Token, form, &Auth::Basic(id, secret)).map(|v| Tokens::read(&v))
    }

    fn open(&mut self) -> Result<Session, Fail> {
        if let Some((id, secret)) = self.ci_pair() {
            let tokens = self.mint(&id, &secret)?;
            return Ok(Session { access: tokens.access, scopes: tokens.scope.unwrap_or_else(|| SCOPES.into()), email: None, cred: Cred::Ci { id, secret } });
        }
        let record = self.app.stores.load(&self.profile)?.ok_or_else(|| Fail::auth("not signed in: run `lingara login`"))?;
        let stale = time::parse(&record.access_expires_at).is_none_or(|at| at - self.app.http.clock.now() < REFRESH_SKEW_SECS);
        let session =
            Session { access: record.access_token.clone(), scopes: record.scopes.clone(), email: Some(record.email.clone()), cred: Cred::Stored { record } };
        self.app.session = Some(session);
        if stale {
            self.refresh()?;
        }
        self.app.session.take().ok_or_else(|| Fail::failure("the session was lost"))
    }

    /// The bearer token, opening the session on first use.
    pub fn bearer(&mut self) -> Result<String, Fail> {
        if self.app.session.is_none() {
            let session = self.open()?;
            self.app.session = Some(session);
        }
        Ok(self.app.session.as_ref().map(|s| s.access.clone()).unwrap_or_default())
    }

    /// Rule 2's one refresh: the stored refresh token, or a fresh CI mint.
    /// A refused refresh never deletes the stored credential (D3).
    fn refresh(&mut self) -> Result<(), Fail> {
        let Some(session) = self.app.session.as_ref() else { return Err(Fail::auth("not signed in: run `lingara login`")) };
        match &session.cred {
            Cred::Ci { id, secret } => {
                let (id, secret) = (id.clone(), secret.clone());
                let tokens = self.mint(&id, &secret)?;
                self.set_access(tokens.access);
            }
            Cred::Stored { record } => {
                let mut record = record.clone();
                let form = vec![
                    ("grant_type".into(), "refresh_token".into()),
                    ("refresh_token".into(), record.refresh_token.clone()),
                    ("client_id".into(), self.app.endpoints.client_id.clone()),
                ];
                let tokens = self.token_post(Fixed::Token, form, &Auth::None).map_err(login_again)?;
                tokens_into(&mut record, &tokens, self.app.http.clock.now());
                self.save_record(&record)?;
                self.app.session = Some(Session {
                    access: record.access_token.clone(),
                    scopes: record.scopes.clone(),
                    email: Some(record.email.clone()),
                    cred: Cred::Stored { record },
                });
            }
        }
        Ok(())
    }

    fn set_access(&mut self, access: String) {
        if let Some(s) = self.app.session.as_mut() {
            s.access = access;
        }
    }

    /// Writes the profile record, printing the store's one fallback warning.
    pub fn save_record(&mut self, record: &ProfileRecord) -> Result<(), Fail> {
        if let Some(warning) = self.app.stores.save(&self.profile, record)? {
            self.out.note(&warning);
        }
        Ok(())
    }

    /// One authenticated call: send, refresh and replay once on a `401`,
    /// classify anything else.
    pub fn call(&mut self, request: &Request) -> Result<Value, Fail> {
        let token = self.bearer()?;
        let reply = self.app.http.send(request, &Auth::Bearer(&token))?;
        if (200..300).contains(&reply.status) {
            return body_json(&reply, request.route);
        }
        if classify(&answer(request.route, &reply, true)) != Verdict::Refresh {
            return Err(refusal(request.route, &reply, true));
        }
        self.refresh()?;
        let token = self.bearer()?;
        let replay = self.app.http.send(request, &Auth::Bearer(&token))?;
        match replay.status {
            200..=299 => body_json(&replay, request.route),
            401 => Err(Fail::auth("the platform refused the credentials after a refresh: run `lingara login`")),
            _ => Err(refusal(request.route, &replay, true)),
        }
    }

    /// The email `--confirm` and the typed line are compared against.
    pub fn email(&mut self) -> Result<String, Fail> {
        self.bearer()?;
        if let Some(email) = self.app.session.as_ref().and_then(|s| s.email.clone()) {
            return Ok(email);
        }
        let me = self.call(&Request::new(&fixed(Fixed::DeveloperMe)))?;
        let email = me["email"].as_str().unwrap_or_default().to_string();
        if let Some(s) = self.app.session.as_mut() {
            s.email = Some(email.clone());
        }
        Ok(email)
    }
}

/// A refused refresh says "run `lingara login`", unless it is D8's dark
/// signal, which has its own sentence.
fn login_again(fail: Fail) -> Fail {
    if fail.exit == crate::exit::Exit::AuthRequired { fail.hint("run `lingara login`") } else { fail }
}

fn tokens_into(record: &mut ProfileRecord, tokens: &Value, now: i64) {
    Tokens::read(tokens).apply(record, now);
}
