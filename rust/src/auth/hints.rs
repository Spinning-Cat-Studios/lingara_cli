//! The one line of advice a refusal's code earns (D3, D5, D6). Keyed by
//! code, so a reworded server sentence changes nothing.

use crate::exit::Fail;

pub const PORTAL_LINE: &str = "create your developer account at https://platform.getlingara.com";

const BY_CODE: [(&str, &str); 5] = [
    ("developer_not_found", PORTAL_LINE),
    ("developer_account_required", PORTAL_LINE),
    ("developer_terms_outdated", "accept the updated developer terms at https://platform.getlingara.com"),
    ("delete_confirmation_mismatch", "the platform holds a different email for you; run `lingara whoami`"),
    ("invalid_scope", "recreate this client with `lingara client create --ci`"),
];

pub fn for_code(code: &str) -> Option<&'static str> {
    BY_CODE.iter().find(|(c, _)| *c == code).map(|(_, line)| *line)
}

/// `fail` with its code's line, once.
pub fn advise(mut fail: Fail) -> Fail {
    if let Some(line) = for_code(&fail.code).filter(|l| !fail.hints.iter().any(|h| h == l)) {
        fail.hints.push(line.to_string());
    }
    fail
}
