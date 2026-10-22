//! Every path this kit sends, spelled once and keyed by
//! `conformance/routes.toml`'s logical names (ADR 16.10.26ad D2). The mock
//! matches by path, so a spelling that drifts from that file meets a `599`
//! and fails conformance.
//!
//! The tables are indexed by their enum, so a route is data, never a branch.

/// Which base a path is under: the API, or the portal's browser pages.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Base {
    Api,
    Portal,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Route {
    /// The logical name: what `unexpected response from {route}` names.
    pub name: String,
    pub method: &'static str,
    pub path: &'static str,
    pub base: Base,
}

impl Route {
    /// `oauth.token` and `oauth.device_authorization`: no bearer, and their
    /// RFC envelope is B2 rule 3's.
    pub fn is_token(&self) -> bool {
        self.name == "oauth.token" || self.name == "oauth.device_authorization"
    }

    pub fn is_platform(&self) -> bool {
        self.path.starts_with("/platform/")
    }
}

/// The routes that are not a kind's `/platform/objects` family.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fixed {
    Authorize,
    DeviceAuthorization,
    Token,
    Revoke,
    DeveloperMe,
    CanvasList,
    CanvasCreate,
    CanvasGet,
    CanvasRename,
    CanvasPutDraft,
    CanvasDryRun,
    CanvasActivate,
    CanvasLookup,
    AppDevGet,
    AppDevPatch,
    AppDevSessionOpen,
    AppDevSessionRenew,
    AppDevSessionEnd,
}

const OBJECTS: &str = "/platform/objects";
const OBJECT: &str = "/platform/objects/{id}";
const CANVAS: &str = "/v1/canvases/{id}";

const DEV: &str = "/platform/objects/{id}/dev";
const DEV_SESSION: &str = "/platform/objects/{id}/dev/session";

const FIXED: [(&str, &str, &str, Base); 18] = [
    ("oauth.authorize", "GET", "/oauth/authorize", Base::Portal),
    ("oauth.device_authorization", "POST", "/oauth/device_authorization", Base::Api),
    ("oauth.token", "POST", "/oauth/token", Base::Api),
    ("oauth.revoke", "POST", "/oauth/revoke", Base::Api),
    ("developer.me", "GET", "/platform/developer", Base::Api),
    ("canvas.list", "GET", "/v1/canvases", Base::Api),
    ("canvas.create", "POST", "/v1/canvases", Base::Api),
    ("canvas.get", "GET", CANVAS, Base::Api),
    ("canvas.rename", "PATCH", CANVAS, Base::Api),
    ("canvas.put_draft", "PUT", "/v1/canvases/{id}/draft", Base::Api),
    ("canvas.dry_run", "POST", "/v1/canvases/dry-run", Base::Api),
    ("canvas.activate", "POST", "/v1/canvases/{id}/activate", Base::Api),
    // `?kind=canvas&runtime_id=…` is the caller's query (D2).
    ("canvas.lookup", "GET", OBJECTS, Base::Api),
    // The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
    ("app_dev.get", "GET", DEV, Base::Api),
    ("app_dev.patch", "PATCH", DEV, Base::Api),
    ("app_dev.session_open", "POST", DEV_SESSION, Base::Api),
    ("app_dev.session_renew", "POST", "/platform/objects/{id}/dev/session/{session_id}/renew", Base::Api),
    ("app_dev.session_end", "DELETE", "/platform/objects/{id}/dev/session/{session_id}", Base::Api),
];

pub fn fixed(route: Fixed) -> Route {
    let (name, method, path, base) = FIXED[route as usize];
    Route { name: name.to_string(), method, path, base }
}

/// One operation of the `/platform/objects` family (16.10.26v §8).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Op {
    List,
    Get,
    Create,
    Rename,
    VersionGet,
    VersionPut,
    VersionCreate,
    Publish,
    Versions,
    Submit,
    Withdraw,
    Delete,
    DeleteVersion,
    SecretCreate,
    SecretDelete,
    Enable,
    Disable,
    Test,
}

const OBJECT_OPS: [(&str, &str, &str); 18] = [
    ("list", "GET", OBJECTS),
    ("get", "GET", OBJECT),
    ("create", "POST", OBJECTS),
    ("rename", "PATCH", OBJECT),
    ("version_get", "GET", "/platform/objects/{id}/versions/{semver}"),
    ("version_put", "PUT", "/platform/objects/{id}/versions/{semver}"),
    ("version_create", "POST", "/platform/objects/{id}/versions"),
    ("publish", "POST", "/platform/objects/{id}/versions/{semver}/publish"),
    ("versions", "GET", "/platform/objects/{id}/versions"),
    ("submit", "POST", "/platform/objects/{id}/versions/{semver}/submit"),
    ("withdraw", "POST", "/platform/objects/{id}/versions/{semver}/withdraw"),
    ("delete", "DELETE", OBJECT),
    ("delete_version", "DELETE", "/platform/objects/{id}/versions/{semver}"),
    ("secret_create", "POST", "/platform/objects/{id}/secrets"),
    ("secret_delete", "DELETE", "/platform/objects/{id}/secrets/{secret_id}"),
    ("enable", "POST", "/platform/objects/{id}/enable"),
    ("disable", "POST", "/platform/objects/{id}/disable"),
    ("test", "POST", "/platform/objects/{id}/test"),
];

/// `<word>.<op>`: `object("webhook_outbound", Op::Get)` is
/// `webhook_outbound.get`, `GET /platform/objects/{id}`.
pub fn object(word: &str, op: Op) -> Route {
    let (suffix, method, path) = OBJECT_OPS[op as usize];
    Route { name: format!("{word}.{suffix}"), method, path, base: Base::Api }
}

/// The same path under another name: `canvas.object_get` is `<word>.get`'s
/// path, renamed because `canvas.get` is the `/v1` read (D2).
pub fn object_named(name: &str, op: Op) -> Route {
    let (_, method, path) = OBJECT_OPS[op as usize];
    Route { name: name.to_string(), method, path, base: Base::Api }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tables_follow_their_enums() {
        assert_eq!(fixed(Fixed::Token).name, "oauth.token");
        assert_eq!(fixed(Fixed::CanvasLookup).name, "canvas.lookup");
        assert_eq!(object("app", Op::Test).path, "/platform/objects/{id}/test");
        assert_eq!(object("app", Op::Submit).name, "app.submit");
        assert_eq!(object_named("canvas.object_get", Op::Get).path, OBJECT);
        assert!(fixed(Fixed::DeviceAuthorization).is_token() && !fixed(Fixed::DeveloperMe).is_token());
    }
}
