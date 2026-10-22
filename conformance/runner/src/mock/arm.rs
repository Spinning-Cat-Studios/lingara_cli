//! A case's `exchanges`, resolved for replay: each `route` to its method,
//! path and query (D4), each `vars` into the path, and every placeholder
//! (`{base_url}`, `{platform_access_code}`, `{platform_disabled_code}`)
//! into the strings it appears in (D3).

use std::collections::BTreeMap;
use std::time::Duration;

use serde_json::Value;

use crate::case::{Case, HeaderMatch, Order, Response};
use crate::routes::Routes;

#[derive(Debug, Clone)]
pub struct Armed {
    pub method: String,
    pub path: String,
    pub query: BTreeMap<String, String>,
    pub headers: BTreeMap<String, HeaderMatch>,
    pub json: Option<Value>,
    pub form: Option<BTreeMap<String, String>>,
    pub response: Response,
    pub times: u32,
    pub group: Option<u32>,
    pub min_gap: Option<Duration>,
}

impl Armed {
    pub fn describe(&self) -> String {
        format!("{} {}", self.method, self.path)
    }
}

/// Every `(placeholder, value)` pair a case's strings may use.
pub fn substitutions(base_url: &str, routes: &Routes) -> Vec<(String, String)> {
    let mut out = vec![("{base_url}".to_string(), base_url.to_string())];
    out.extend(routes.placeholders().iter().map(|(k, v)| (k.to_string(), v.to_string())));
    out
}

pub fn fill(text: &str, subs: &[(String, String)]) -> String {
    subs.iter().fold(text.to_string(), |acc, (k, v)| acc.replace(k, v))
}

/// Every string in `value`, filled.
pub fn fill_json(value: &Value, subs: &[(String, String)]) -> Value {
    match value {
        Value::String(s) => Value::String(fill(s, subs)),
        Value::Array(a) => Value::Array(a.iter().map(|v| fill_json(v, subs)).collect()),
        Value::Object(o) => Value::Object(o.iter().map(|(k, v)| (k.clone(), fill_json(v, subs))).collect()),
        other => other.clone(),
    }
}

/// The case's items in order, and its order. A route `check` already
/// refused never reaches here; an unknown one is skipped defensively.
pub fn arm(case: &Case, routes: &Routes, base_url: &str) -> (Order, Vec<Armed>) {
    let subs = substitutions(base_url, routes);
    let Some(exchanges) = &case.exchanges else { return (Order::Sequence, Vec::new()) };
    let mut items = Vec::new();
    for item in &exchanges.items {
        let (method, path, mut query) = match item.route.as_deref().and_then(|r| routes.get(r)) {
            Some(route) => (route.method.clone(), route.path.clone(), route.query.clone()),
            None => (
                item.request.method.clone().unwrap_or_default(),
                item.request.path.clone().unwrap_or_default(),
                BTreeMap::new(),
            ),
        };
        let vars: Vec<(String, String)> = item.vars.iter().map(|(k, v)| (format!("{{{k}}}"), v.clone())).collect();
        query.extend(item.request.query.clone().unwrap_or_default());
        items.push(Armed {
            method,
            path: fill(&path, &vars),
            query,
            headers: fill_headers(item.request.headers.clone().unwrap_or_default(), &subs),
            json: item.request.json.as_ref().map(|j| fill_json(j, &subs)),
            form: item.request.form.clone(),
            response: fill_response(&item.response, &subs),
            times: item.times.unwrap_or(1),
            group: item.group,
            min_gap: item.min_gap_ms.map(Duration::from_millis),
        });
    }
    (exchanges.order, items)
}

fn fill_headers(headers: BTreeMap<String, HeaderMatch>, subs: &[(String, String)]) -> BTreeMap<String, HeaderMatch> {
    let fill_one = |m: HeaderMatch| match m {
        HeaderMatch::Equals(s) => HeaderMatch::Equals(fill(&s, subs)),
        HeaderMatch::Prefix(s) => HeaderMatch::Prefix(fill(&s, subs)),
        HeaderMatch::Contains(s) => HeaderMatch::Contains(fill(&s, subs)),
        other => other,
    };
    headers.into_iter().map(|(k, m)| (k.to_ascii_lowercase(), fill_one(m))).collect()
}

fn fill_response(response: &Response, subs: &[(String, String)]) -> Response {
    let mut out = response.clone();
    out.json = response.json.as_ref().map(|j| fill_json(j, subs));
    out.text = response.text.as_ref().map(|t| fill(t, subs));
    out.headers = response.headers.as_ref().map(|h| h.iter().map(|(k, v)| (k.clone(), fill_json(v, subs))).collect());
    // A redirect's params too: an authorize endpoint's RFC 9207 `iss` is the
    // mock's own `{base_url}` (ADR 16.10.26ad's b6 cases).
    if let Some(redirect) = out.redirect.as_mut() {
        redirect.params = redirect.params.iter().map(|(k, v)| (k.clone(), fill(v, subs))).collect();
    }
    out
}
