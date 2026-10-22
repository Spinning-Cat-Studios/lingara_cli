//! A token response, and the profile record it becomes (D4).

use serde_json::Value;

use crate::store::ProfileRecord;
use crate::time;

/// RFC 6749 §5.1, read leniently: a missing `expires_in` is an hour.
pub struct Tokens {
    pub access: String,
    pub refresh: Option<String>,
    pub scope: Option<String>,
    pub expires_in: i64,
}

impl Tokens {
    pub fn read(value: &Value) -> Tokens {
        let text = |k: &str| value.get(k).and_then(Value::as_str).map(str::to_string);
        Tokens {
            access: text("access_token").unwrap_or_default(),
            refresh: text("refresh_token"),
            scope: text("scope"),
            expires_in: value.get("expires_in").and_then(Value::as_i64).unwrap_or(3600),
        }
    }

    /// `record` with these tokens: the refresh token rotates when one was
    /// sent, and `scopes` is rewritten whenever the answer carried `scope`.
    pub fn apply(&self, record: &mut ProfileRecord, now: i64) {
        record.access_token = self.access.clone();
        if let Some(refresh) = &self.refresh {
            record.refresh_token = refresh.clone();
        }
        if let Some(scope) = &self.scope {
            record.scopes = scope.clone();
        }
        record.access_expires_at = time::format(now + self.expires_in);
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn a_refresh_rewrites_tokens_scope_and_expiry() {
        let mut record = ProfileRecord { refresh_token: "old".into(), scopes: "a".into(), ..Default::default() };
        Tokens::read(&json!({ "access_token": "at", "expires_in": 60 })).apply(&mut record, 0);
        assert_eq!((record.access_token.as_str(), record.refresh_token.as_str(), record.scopes.as_str()), ("at", "old", "a"));
        assert_eq!(record.access_expires_at, "1970-01-01T00:01:00Z");
        Tokens::read(&json!({ "access_token": "b", "refresh_token": "new", "scope": "x y" })).apply(&mut record, 0);
        assert_eq!((record.refresh_token.as_str(), record.scopes.as_str()), ("new", "x y"));
    }
}
