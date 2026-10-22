//! The `exchanges` block: the API clients' (ADR 29.9.26n D10) without its
//! stream keys, plus D3's `route`, `vars`, `redirect` and `min_gap_ms`.

use std::collections::BTreeMap;

use serde::Deserialize;
use serde_json::Value;

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Exchanges {
    #[serde(default)]
    pub order: Order,
    pub items: Vec<Exchange>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Order {
    #[default]
    Sequence,
    Any,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Exchange {
    /// A `routes.toml` name; or `request.method` and `request.path`.
    pub route: Option<String>,
    /// Fill `{name}` in the route's path.
    #[serde(default)]
    pub vars: BTreeMap<String, String>,
    pub times: Option<u32>,
    pub group: Option<u32>,
    /// Since the previous item matched; arriving earlier is a mismatch.
    pub min_gap_ms: Option<u64>,
    #[serde(default)]
    pub request: Request,
    pub response: Response,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub method: Option<String>,
    pub path: Option<String>,
    pub query: Option<BTreeMap<String, String>>,
    pub headers: Option<BTreeMap<String, HeaderMatch>>,
    pub json: Option<Value>,
    pub form: Option<BTreeMap<String, String>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Response {
    pub delay_ms: Option<u64>,
    /// Absent only beside `redirect`, which is a `302`.
    pub status: Option<u16>,
    pub headers: Option<BTreeMap<String, Value>>,
    pub json: Option<Value>,
    pub text: Option<String>,
    pub redirect: Option<Redirect>,
}

impl Response {
    pub fn status(&self) -> u16 {
        self.status.unwrap_or(if self.redirect.is_some() { 302 } else { 200 })
    }

    /// No `json` and no `text`: an empty body.
    pub fn is_empty(&self) -> bool {
        self.json.is_none() && self.text.is_none()
    }
}

/// A `302` to the URL in the request's `to_query` parameter, with `params`
/// added and each `echo` parameter copied from the request: what an
/// authorize endpoint does with `redirect_uri`, `code` and `state`.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Redirect {
    pub to_query: String,
    #[serde(default)]
    pub params: BTreeMap<String, String>,
    #[serde(default)]
    pub echo: Vec<String>,
}

/// One header matcher, written as a one-key map (`{ prefix: … }`).
#[derive(Debug, Clone, Deserialize)]
#[serde(try_from = "HeaderMatchSpec")]
pub enum HeaderMatch {
    Equals(String),
    Prefix(String),
    Contains(String),
    Pattern(String),
    Absent(bool),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HeaderMatchSpec {
    equals: Option<String>,
    prefix: Option<String>,
    contains: Option<String>,
    pattern: Option<String>,
    absent: Option<bool>,
}

impl TryFrom<HeaderMatchSpec> for HeaderMatch {
    type Error = String;

    fn try_from(spec: HeaderMatchSpec) -> Result<Self, String> {
        let all = [
            spec.equals.map(HeaderMatch::Equals),
            spec.prefix.map(HeaderMatch::Prefix),
            spec.contains.map(HeaderMatch::Contains),
            spec.pattern.map(HeaderMatch::Pattern),
            spec.absent.map(HeaderMatch::Absent),
        ];
        let mut set = all.into_iter().flatten();
        match (set.next(), set.next()) {
            (Some(one), None) => Ok(one),
            _ => Err("a matcher is exactly one of equals, prefix, contains, pattern, absent".into()),
        }
    }
}
