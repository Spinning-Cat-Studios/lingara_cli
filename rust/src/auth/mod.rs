//! Authentication (ADR 16.10.26ad D3; contract B6): CI client credentials,
//! a stored credential from `login`, or neither, in that order.

pub mod device;
pub mod hints;
pub mod pkce;
pub mod session;
pub mod tokens;

/// The four scopes, in one grant: the platform pair opens `/platform/*`,
/// the canvas pair `/v1/canvases` (16.10.26u §3).
pub const SCOPES: &str = "platform:read platform:write canvas:read canvas:write";

/// A stored access token this close to expiry is refreshed first (D3).
pub const REFRESH_SKEW_SECS: i64 = 60;
