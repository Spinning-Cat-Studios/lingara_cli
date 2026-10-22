//! `mock: platform_dev` (ADR 16.10.26ae D11): Backend 16.10.26v §12's dev
//! version and its session as a small stateful model, so an `apps dev` case
//! can renew, re-point and be taken over without scripting every exchange.
//! Routes outside the `app_dev.*` group still come from `exchanges`.
//!
//! Everything it mints is deterministic, so a case can name it: session n's
//! id is `5e55a0c0-0000-4000-8000-<n, 12 digits>`, its app secret
//! `lgr_whsec_conformanceSession<n>Secret`, its client secret
//! `lgr_cs_conformanceSession<n>Client`.

use std::collections::VecDeque;

use serde_json::{Value, json};

use super::http::HttpRequest;
use crate::case::apps::{MockSeed, RenewAnswer};
use crate::routes::{Route, Routes, path_fits};

pub const LEASE_EXPIRES_AT: &str = "2026-10-20T14:05:00Z";
pub const SESSION_EXPIRES_AT: &str = "2026-10-21T02:00:00Z";
const DEFAULT_LEASE_SECS: u64 = 300;

/// One call the model answered, for `expect.calls`.
#[derive(Debug, Clone)]
pub struct DevCall {
    pub route: String,
    pub json: Option<Value>,
}

/// The secrets one open minted.
#[derive(Debug, Clone)]
pub struct Minted {
    pub app_secret: String,
    pub client_secret: Option<String>,
}

#[derive(Debug)]
pub struct DevModel {
    routes: Vec<(String, Route)>,
    lease_secs: u64,
    render_url: Option<String>,
    manifest: Value,
    draft_version: i64,
    /// The session holding a live lease, and until when.
    live: Option<(String, String)>,
    opened: u32,
    renews: VecDeque<RenewAnswer>,
    conflicts_left: u32,
    pub minted: Vec<Minted>,
    pub calls: Vec<DevCall>,
}

fn refusal(code: &str) -> Value {
    json!({ "code": code, "error": format!("conformance mock: {code}") })
}

impl DevModel {
    pub fn new(seed: &MockSeed, routes: &Routes) -> DevModel {
        DevModel {
            routes: routes.group("app_dev"),
            lease_secs: seed.lease_secs.unwrap_or(DEFAULT_LEASE_SECS),
            render_url: seed.render_url.clone(),
            manifest: json!({}),
            draft_version: 1,
            live: seed.held_until.clone().map(|until| ("another-terminal".to_string(), until)),
            opened: 0,
            renews: seed.renew.iter().copied().collect(),
            conflicts_left: seed.version_conflicts,
            minted: Vec::new(),
            calls: Vec::new(),
        }
    }

    fn route_of(&self, request: &HttpRequest) -> Option<String> {
        let fits = |(_, r): &&(String, Route)| r.method == request.method && path_fits(&r.path, &request.path);
        self.routes.iter().find(fits).map(|(n, _)| n.clone())
    }

    fn view(&self) -> Value {
        let (state, session, lease) = match &self.live {
            Some((sid, until)) => ("live", Some(sid.clone()), Some(until.clone())),
            None => ("parked", None, None),
        };
        let expires = lease.as_ref().map(|_| SESSION_EXPIRES_AT);
        json!({
            "manifest": self.manifest,
            "render_url": self.render_url,
            "draft_version": self.draft_version,
            "session": { "state": state, "session_id": session, "lease_expires_at": lease, "expires_at": expires },
        })
    }

    /// The answer when `request` is one of the group's routes; `None` hands
    /// it to `exchanges`.
    pub fn answer(&mut self, request: &HttpRequest) -> Option<(u16, Option<Value>)> {
        let route = self.route_of(request)?;
        let body: Option<Value> = serde_json::from_slice(&request.body).ok();
        self.calls.push(DevCall { route: route.clone(), json: body.clone() });
        let sid = request.path.split('/').nth(6).unwrap_or("").to_string();
        let body = body.unwrap_or(Value::Null);
        Some(match route.as_str() {
            "app_dev.get" => (200, Some(self.view())),
            "app_dev.patch" => self.patch(&body),
            "app_dev.session_open" => self.open(&body),
            "app_dev.session_renew" => self.renew(&sid),
            _ => self.end(&sid),
        })
    }

    fn held(&self) -> (u16, Option<Value>) {
        let mut answer = refusal("dev_session_held");
        answer["lease_expires_at"] = json!(self.live.as_ref().map(|(_, until)| until.clone()));
        (409, Some(answer))
    }

    fn patch(&mut self, body: &Value) -> (u16, Option<Value>) {
        if self.conflicts_left > 0 || body["draft_version"].as_i64() != Some(self.draft_version) {
            self.conflicts_left = self.conflicts_left.saturating_sub(1);
            return (409, Some(refusal("version_conflict")));
        }
        let new_url = body.get("render_url").map(|u| u.as_str().map(str::to_string));
        let moves = new_url.as_ref().is_some_and(|u| *u != self.render_url);
        let holder = self.live.as_ref().map(|(sid, _)| sid.as_str());
        if moves && holder.is_some() && body["session_id"].as_str() != holder {
            return self.held();
        }
        if let Some(url) = new_url {
            self.render_url = url;
        }
        if let Some(manifest) = body.get("manifest") {
            self.manifest = manifest.clone();
        }
        self.draft_version += 1;
        (200, Some(self.view()))
    }

    fn open(&mut self, body: &Value) -> (u16, Option<Value>) {
        if self.live.is_some() && body["take_over"] != true {
            return self.held();
        }
        self.opened += 1;
        let n = self.opened;
        let sid = format!("5e55a0c0-0000-4000-8000-{n:012}");
        let client = body["credentials"].as_array().is_some_and(|c| c.iter().any(|x| x == "client"));
        let minted = Minted {
            app_secret: format!("lgr_whsec_conformanceSession{n}Secret"),
            client_secret: client.then(|| format!("lgr_cs_conformanceSession{n}Client")),
        };
        let secret = |s: &str, id: &str| json!({ "secret_id": id, "prefix": &s[..12], "secret": s });
        let answer = json!({
            "session_id": sid,
            "lease_secs": self.lease_secs,
            "lease_expires_at": LEASE_EXPIRES_AT,
            "expires_at": SESSION_EXPIRES_AT,
            "app_secret": secret(&minted.app_secret, "a5ec0000-0000-4000-8000-000000000001"),
            "client_secret": minted.client_secret.as_deref().map(|s| secret(s, "c5ec0000-0000-4000-8000-000000000001")),
        });
        self.live = Some((sid, LEASE_EXPIRES_AT.to_string()));
        self.minted.push(minted);
        (201, Some(answer))
    }

    fn renew(&mut self, sid: &str) -> (u16, Option<Value>) {
        if self.live.as_ref().is_none_or(|(live, _)| live != sid) {
            return (409, Some(refusal("dev_session_not_live")));
        }
        match self.renews.pop_front().unwrap_or(RenewAnswer::Ok) {
            RenewAnswer::Ok => (200, Some(json!({ "lease_expires_at": LEASE_EXPIRES_AT, "expires_at": SESSION_EXPIRES_AT }))),
            RenewAnswer::NotLive => {
                self.live = None;
                (409, Some(refusal("dev_session_not_live")))
            }
            RenewAnswer::Expired => {
                self.live = None;
                (409, Some(refusal("dev_session_expired")))
            }
        }
    }

    fn end(&mut self, sid: &str) -> (u16, Option<Value>) {
        if self.live.as_ref().is_none_or(|(live, _)| live != sid) {
            return (409, Some(refusal("dev_session_not_live")));
        }
        self.live = None;
        (204, None)
    }
}
