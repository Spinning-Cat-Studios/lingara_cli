//! `login --device` (RFC 8628, 16.10.26u §7): for SSH sessions and machines
//! without a browser. `authorization_pending` keeps polling, `slow_down`
//! adds 5 s, `expired_token` and `access_denied` are `auth_required`, and
//! any answer carrying B4's `lingara_code` is `platform_disabled` (D8).

use serde_json::Value;

use super::SCOPES;
use super::session::refusal;
use crate::app::Ctx;
use crate::assets::message;
use crate::exit::Fail;
use crate::http::routes::{Fixed, fixed};
use crate::http::{Auth, Body, Request};

const GRANT: &str = "urn:ietf:params:oauth:grant-type:device_code";

struct Started {
    device_code: String,
    interval: u64,
    expires_in: u64,
}

fn started(value: &Value) -> Started {
    Started {
        device_code: value["device_code"].as_str().unwrap_or_default().to_string(),
        interval: value["interval"].as_u64().unwrap_or(5),
        expires_in: value["expires_in"].as_u64().unwrap_or(900),
    }
}

impl Ctx<'_> {
    /// The token response once the developer has approved the code.
    pub fn device_login(&mut self) -> Result<Value, Fail> {
        let client_id = self.app.endpoints.client_id.clone();
        let form = vec![("client_id".into(), client_id.clone()), ("scope".into(), SCOPES.into())];
        let start = self.token_post(Fixed::DeviceAuthorization, form, &Auth::None)?;
        let text = |k: &str| start[k].as_str().unwrap_or_default().to_string();
        let shown = message("login_device_code", &[("verification_uri", &text("verification_uri")), ("user_code", &text("user_code"))]);
        self.out.note(&shown);
        if let Some(complete) = start["verification_uri_complete"].as_str() {
            self.out.note(complete);
        }
        self.poll(started(&start), &client_id)
    }

    fn poll(&mut self, mut s: Started, client_id: &str) -> Result<Value, Fail> {
        let route = fixed(Fixed::Token);
        let mut waited = 0;
        loop {
            self.app.http.clock.sleep(s.interval);
            waited += s.interval;
            let mut request = Request::new(&route);
            let form = [("grant_type", GRANT), ("device_code", s.device_code.as_str()), ("client_id", client_id)];
            request.body = Body::Form(form.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect());
            let reply = self.app.http.send(&request, &Auth::None)?;
            if (200..300).contains(&reply.status) {
                return serde_json::from_slice(&reply.body).map_err(|_| Fail::failure("unexpected response from oauth.token"));
            }
            let body: Value = serde_json::from_slice(&reply.body).unwrap_or(Value::Null);
            let error = if body.get("lingara_code").is_some() { None } else { body["error"].as_str() };
            match error {
                Some("authorization_pending") if waited < s.expires_in => {}
                Some("slow_down") if waited < s.expires_in => s.interval += 5,
                Some("authorization_pending" | "slow_down" | "expired_token") => {
                    return Err(Fail::auth("the code expired before it was approved; run `lingara login --device` again"));
                }
                Some("access_denied") => return Err(Fail::new(crate::exit::Exit::AuthRequired, "access_denied", "sign-in was declined")),
                _ => return Err(refusal(&route, &reply, false)),
            }
        }
    }
}
