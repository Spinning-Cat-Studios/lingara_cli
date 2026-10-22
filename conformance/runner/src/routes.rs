//! `conformance/routes.toml` (ADR 16.10.26ac D4): every developer route a
//! case calls, by a logical name, so a Backend rename is one line here and
//! no case.

use std::collections::BTreeMap;
use std::path::Path;

use serde::Deserialize;

#[derive(Debug, Clone)]
pub struct Routes {
    pub confirm_body_field: String,
    pub platform_disabled_code: String,
    pub platform_access_code: String,
    pub routes: BTreeMap<String, Route>,
    pub destructive: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Route {
    pub method: String,
    /// Without its query: `/platform/objects`.
    pub path: String,
    /// The `?…` half, split before matching (D3).
    pub query: BTreeMap<String, String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawRoutes {
    confirm_body_field: String,
    platform_disabled_code: String,
    platform_access_code: String,
    route: BTreeMap<String, String>,
    destructive: Vec<String>,
}

impl Routes {
    pub fn get(&self, name: &str) -> Option<&Route> {
        self.routes.get(name)
    }

    /// Every route whose name starts `<group>.`: `app_dev` is what a case's
    /// `mock: platform_dev` serves from its model (ADR 16.10.26ae D11).
    pub fn group(&self, group: &str) -> Vec<(String, Route)> {
        let prefix = format!("{group}.");
        self.routes.iter().filter(|(n, _)| n.starts_with(&prefix)).map(|(n, r)| (n.clone(), r.clone())).collect()
    }

    /// Whether a request is to a destructive route: the method matches and
    /// each `{var}` segment of the route's path matches any one segment.
    pub fn is_destructive(&self, method: &str, path: &str) -> bool {
        let named = self.destructive.iter().filter_map(|n| self.routes.get(n));
        named.into_iter().any(|r| r.method == method && path_fits(&r.path, path))
    }

    /// `{base_url}`-style placeholders a case's strings may use (D3).
    pub fn placeholders(&self) -> [(&'static str, &str); 2] {
        [
            ("{platform_access_code}", self.platform_access_code.as_str()),
            ("{platform_disabled_code}", self.platform_disabled_code.as_str()),
        ]
    }
}

/// Whether `actual` fits `template`, segment by segment.
pub fn path_fits(template: &str, actual: &str) -> bool {
    let (t, a): (Vec<&str>, Vec<&str>) = (template.split('/').collect(), actual.split('/').collect());
    t.len() == a.len() && t.iter().zip(&a).all(|(t, a)| (t.starts_with('{') && !a.is_empty()) || t == a)
}

pub fn load(path: &Path) -> Result<Routes, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    parse(&text).map_err(|e| format!("{}: {e}", path.display()))
}

pub fn parse(text: &str) -> Result<Routes, String> {
    let raw: RawRoutes = toml::from_str(text).map_err(|e| e.to_string())?;
    let mut routes = BTreeMap::new();
    for (name, spelled) in &raw.route {
        routes.insert(name.clone(), route_of(name, spelled)?);
    }
    for name in &raw.destructive {
        if !routes.contains_key(name) {
            return Err(format!("destructive names `{name}`, which is not a route"));
        }
    }
    for (name, route) in &routes {
        if route.method == "DELETE" && !raw.destructive.contains(name) {
            return Err(format!("route `{name}` is a DELETE missing from `destructive`"));
        }
    }
    Ok(Routes {
        confirm_body_field: raw.confirm_body_field,
        platform_disabled_code: raw.platform_disabled_code,
        platform_access_code: raw.platform_access_code,
        routes,
        destructive: raw.destructive,
    })
}

fn route_of(name: &str, spelled: &str) -> Result<Route, String> {
    let Some((method, target)) = spelled.split_once(' ') else {
        return Err(format!("route `{name}` is not `METHOD /path`: {spelled:?}"));
    };
    if !matches!(method, "GET" | "POST" | "PUT" | "PATCH" | "DELETE") || !target.starts_with('/') {
        return Err(format!("route `{name}` is not `METHOD /path`: {spelled:?}"));
    }
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let query = query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let (k, v) = p.split_once('=').unwrap_or((p, ""));
            (k.to_string(), v.to_string())
        })
        .collect();
    Ok(Route { method: method.to_string(), path: path.to_string(), query })
}
