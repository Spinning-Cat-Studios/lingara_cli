//! `login`, `logout` and `whoami` (D3). Under CI credentials there is
//! nothing to sign in or out of, so both exit `usage` with
//! `login_ci_not_needed`.

use serde_json::Value;

use crate::app::Ctx;
use crate::assets::message;
use crate::auth::session::{Cred, Session};
use crate::auth::tokens::Tokens;
use crate::exit::{Exit, Fail};
use crate::http::routes::{Fixed, fixed};
use crate::http::{Auth, Body, Request};
use crate::output::table::{cell, render};
use crate::store::ProfileRecord;

fn ci_not_needed() -> Fail {
    Fail::new(Exit::Usage, "login_ci_not_needed", message("login_ci_not_needed", &[]))
}

pub fn login(ctx: &mut Ctx, device: bool) -> Result<(), Fail> {
    if ctx.ci_pair().is_some() {
        return Err(ci_not_needed());
    }
    let tokens = if device { ctx.device_login()? } else { ctx.browser_login()? };
    let mut record = ProfileRecord::default();
    Tokens::read(&tokens).apply(&mut record, ctx.app.http.clock.now());
    // The new token, for this one read: whose is it?
    let access = record.access_token.clone();
    ctx.app.session = Some(Session { cred: Cred::Stored { record: record.clone() }, access, email: None, scopes: record.scopes.clone() });
    let me = ctx.call(&Request::new(&fixed(Fixed::DeveloperMe)))?;
    record.developer_id = text(&me, "developer_id");
    record.email = text(&me, "email");
    ctx.save_record(&record)?;
    ctx.out.note(&message("login_signed_in", &[("email", &record.email)]));
    ctx.out.nothing();
    Ok(())
}

fn text(value: &Value, key: &str) -> String {
    value[key].as_str().unwrap_or_default().to_string()
}

/// Revokes the refresh token (RFC 7009) and forgets the profile. A revoke
/// that fails, the network being down say, still forgets it and warns.
pub fn logout(ctx: &mut Ctx) -> Result<(), Fail> {
    if ctx.ci_pair().is_some() {
        return Err(ci_not_needed());
    }
    let record = ctx.app.stores.load(&ctx.profile)?.ok_or_else(|| Fail::auth("not signed in: nothing to sign out of"))?;
    let route = fixed(Fixed::Revoke);
    let mut request = Request::new(&route);
    let form = [("token", record.refresh_token.as_str()), ("token_type_hint", "refresh_token"), ("client_id", &ctx.app.endpoints.client_id)];
    request.body = Body::Form(form.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect());
    let revoked = ctx.app.http.send(&request, &Auth::Bearer(&record.access_token));
    if !revoked.as_ref().is_ok_and(|r| (200..300).contains(&r.status)) {
        let why = revoked.map_or_else(|f| f.message, |r| format!("HTTP {}", r.status));
        ctx.out.note(&format!("warning: the refresh token could not be revoked ({why}); forgetting it here anyway"));
    }
    ctx.app.stores.delete(&ctx.profile)?;
    ctx.app.session = None;
    ctx.out.nothing();
    Ok(())
}

/// `EMAIL  DEVELOPER  SCOPES  PROFILE`, `SCOPES` from the token response.
pub fn whoami(ctx: &mut Ctx) -> Result<(), Fail> {
    let me = ctx.call(&Request::new(&fixed(Fixed::DeveloperMe)))?;
    if ctx.out.json {
        ctx.out.json_value(&me);
        return Ok(());
    }
    let scopes = ctx.app.session.as_ref().map(|s| s.scopes.clone()).unwrap_or_default();
    let row = vec![cell(&me["email"]), cell(&me["developer_id"]), scopes, ctx.profile.clone()];
    ctx.out.print(&render(&["EMAIL", "DEVELOPER", "SCOPES", "PROFILE"], &[row]));
    Ok(())
}
