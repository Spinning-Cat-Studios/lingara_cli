use super::*;

fn answer<'a>(route: &'a str, status: u16, body: &'a str) -> Answer<'a> {
    let token_route = route == "oauth.token" || route == "oauth.device_authorization";
    let platform_route = route.contains('.') && !token_route && !route.starts_with("canvas.");
    Answer { route, token_route, platform_route, bearer: !token_route, status, body: body.as_bytes(), retry_after: None }
}

fn exit_of(route: &str, status: u16, body: &str) -> Verdict {
    classify(&answer(route, status, body))
}

const E: fn(Exit) -> Verdict = Verdict::Exit;

/// 16.10.26ad AC7 platform disabled is not not-found
#[test]
fn platform_disabled_is_not_not_found() {
    assert_eq!(exit_of("app.list", 404, r#"{"code":"platform_disabled","error":"x"}"#), E(Exit::PlatformDisabled));
    assert_eq!(exit_of("developer.me", 403, r#"{"code":"platform_access_not_enabled","error":"x"}"#), E(Exit::PlatformDisabled));
    for error in ["access_denied", "invalid_grant", "unauthorized_client"] {
        let body = format!(r#"{{"error":"{error}","error_description":"anything at all","lingara_code":"platform_access_not_enabled"}}"#);
        assert_eq!(exit_of("oauth.token", 400, &body), E(Exit::PlatformDisabled), "{error}");
    }
    // The same error with no lingara_code, or another one, is rule 3.
    let worded = r#"{"error":"invalid_grant","error_description":"The platform isn't enabled for this account yet."}"#;
    assert_eq!(exit_of("oauth.token", 400, worded), E(Exit::AuthRequired));
    let other = r#"{"error":"invalid_grant","lingara_code":"developer_account_required"}"#;
    assert_eq!(exit_of("oauth.token", 400, other), E(Exit::AuthRequired));
    // Coded 404s.
    assert_eq!(exit_of("app.get", 404, r#"{"code":"object_not_found","error":"x"}"#), E(Exit::NotFound));
    assert_eq!(exit_of("developer.me", 404, r#"{"code":"developer_not_found","error":"x"}"#), E(Exit::Refused));
    assert_eq!(exit_of("app.get", 404, r#"{"code":"developer_not_found","error":"x"}"#), E(Exit::NotFound));
    assert_eq!(exit_of("app.list", 404, ""), E(Exit::Failure));
    // A 401 refreshes; a token route's 401 never does.
    assert_eq!(exit_of("app.list", 401, ""), Verdict::Refresh);
    assert_eq!(exit_of("oauth.token", 401, r#"{"error":"invalid_client"}"#), E(Exit::AuthRequired));
    // A 429 or 5xx from oauth.token is never rule 3.
    assert_eq!(exit_of("oauth.token", 429, r#"{"error":"slow"}"#), E(Exit::Unavailable));
    assert_eq!(exit_of("oauth.token", 503, r#"{"error":"down"}"#), E(Exit::Unavailable));
    assert_eq!(exit_of("app.list", 405, ""), E(Exit::Failure));
    assert_eq!(exit_of("app.list", 404, "<html>"), E(Exit::Failure));
    for status in [400, 403, 409, 422] {
        assert_eq!(exit_of("app.create", status, r#"{"code":"c","error":"e"}"#), E(Exit::Refused));
    }
    assert_eq!(exit_of("app.list", 429, ""), E(Exit::Unavailable));
    assert_eq!(exit_of("app.list", 503, "down for maintenance"), E(Exit::Unavailable));
}

#[test]
fn the_printed_refusal_keeps_the_server_code() {
    let a = answer("app.get", 404, r#"{"code":"object_not_found","error":"No such object."}"#);
    let fail = fail_for(&a, Exit::NotFound);
    assert_eq!((fail.code.as_str(), fail.message.as_str()), ("object_not_found", "No such object."));
    let empty = fail_for(&answer("app.list", 404, ""), Exit::Failure);
    assert!(empty.message.contains("app.list"));
    let dark = fail_for(&answer("app.list", 404, r#"{"code":"platform_disabled","error":"raw"}"#), Exit::PlatformDisabled);
    assert_eq!(dark.message, "The Lingara platform isn't enabled for this account yet.");
    let mut limited = answer("app.create", 429, "");
    limited.retry_after = Some(30);
    assert!(fail_for(&limited, Exit::Unavailable).message.ends_with("(retry after 30 s)"));
}

/// 16.10.26ae D10 rule 4a: a dev-session 409 is its own row, keyed on route
/// and body code, before rule 5 would call it `refused`.
#[test]
fn dev_session_refusals_are_their_own_rows() {
    let held = r#"{"code":"dev_session_held","error":"x","lease_expires_at":"2026-10-20T15:00:00Z"}"#;
    assert_eq!(exit_of("app_dev.session_open", 409, held), E(Exit::DevSessionHeld));
    assert_eq!(exit_of("app_dev.patch", 409, held), E(Exit::DevSessionHeld));
    assert_eq!(exit_of("app_dev.session_renew", 409, r#"{"code":"dev_session_not_live","error":"x"}"#), E(Exit::DevSessionLost));
    assert_eq!(exit_of("app_dev.session_renew", 409, r#"{"code":"dev_session_expired","error":"x"}"#), E(Exit::DevSessionLost));
    // Keyed on the route too: the same code elsewhere, or another 409 here, is rule 5's.
    assert_eq!(exit_of("app.create", 409, held), E(Exit::Refused));
    assert_eq!(exit_of("app_dev.patch", 409, r#"{"code":"version_conflict","error":"x"}"#), E(Exit::Refused));
    let fail = fail_for(&answer("app_dev.session_open", 409, held), Exit::DevSessionHeld);
    assert!(fail.body.as_deref().is_some_and(|b| b.contains("lease_expires_at")), "the body rides along for the re-wording");
}
