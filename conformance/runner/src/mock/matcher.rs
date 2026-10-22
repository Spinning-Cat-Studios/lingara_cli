//! Request matching, copied from the API clients' server (ADR 29.9.26n
//! D10) and extended with D3's `min_gap_ms`.
//!
//! An armed case's exchanges form *units*: under `order: sequence` each item
//! is its own unit unless consecutive items share a `group`, which makes them
//! one; under `order: any` every item is one unit. Units are consumed in
//! order, and inside the current unit any item with `times` left may match.

use std::time::Instant;

use regex::Regex;
use serde_json::Value;

use super::arm::Armed;
use super::http::{HttpRequest, form_pairs};
use crate::case::{HeaderMatch, Order, Response};
use crate::routes::path_fits;

/// The exchange state of one armed case.
#[derive(Debug)]
pub struct Exchanges {
    items: Vec<Armed>,
    remaining: Vec<u32>,
    units: Vec<Vec<usize>>,
    last_match: Option<Instant>,
}

impl Exchanges {
    pub fn new(order: Order, items: Vec<Armed>, armed_at: Instant) -> Self {
        let remaining = items.iter().map(|i| i.times).collect();
        let units = units_of(order, &items);
        Self { items, remaining, units, last_match: Some(armed_at) }
    }

    /// Consumes the item `request` matches and returns its response, or
    /// says why nothing in the current unit matched. Nothing is consumed on
    /// a mismatch.
    pub fn take(&mut self, request: &HttpRequest, now: Instant) -> Result<Response, String> {
        let at = format!("{} {}", request.method, request.path);
        let Some(unit) = self.units.iter().find(|u| u.iter().any(|&i| self.remaining[i] > 0)) else {
            return Err(format!("{at}: every expected exchange was already consumed"));
        };
        let open: Vec<usize> = unit.iter().copied().filter(|&i| self.remaining[i] > 0).collect();
        let expected: Vec<String> = open.iter().map(|&i| self.items[i].describe()).collect();
        let same_route = open.into_iter().filter(|&i| routes_match(&self.items[i], request));
        let mut first_failures = None;
        for i in same_route.collect::<Vec<_>>() {
            let mut failures = check_against(&self.items[i], request);
            failures.extend(self.gap_failure(i, now));
            if failures.is_empty() {
                self.remaining[i] -= 1;
                self.last_match = Some(now);
                return Ok(self.items[i].response.clone());
            }
            first_failures.get_or_insert(failures);
        }
        Err(match first_failures {
            Some(f) => format!("{at}: {}", f.join("; ")),
            None => format!("{at}: not the next expected request (expected {})", expected.join(" or ")),
        })
    }

    fn gap_failure(&self, i: usize, now: Instant) -> Option<String> {
        let gap = self.items[i].min_gap?;
        let since = now.duration_since(self.last_match?);
        (since < gap).then(|| format!("arrived {} ms after the previous match, before min_gap_ms {}", since.as_millis(), gap.as_millis()))
    }

    /// One line per item not consumed its `times`.
    pub fn unconsumed(&self) -> Vec<String> {
        let items = self.items.iter().zip(&self.remaining).filter(|(_, left)| **left > 0);
        items.map(|(item, left)| format!("{}: {left} more expected", item.describe())).collect()
    }
}

fn units_of(order: Order, items: &[Armed]) -> Vec<Vec<usize>> {
    if order == Order::Any {
        return vec![(0..items.len()).collect()];
    }
    let mut units: Vec<(Option<u32>, Vec<usize>)> = Vec::new();
    for (i, item) in items.iter().enumerate() {
        match units.last_mut() {
            Some((Some(g), members)) if item.group == Some(*g) => members.push(i),
            _ => units.push((item.group, vec![i])),
        }
    }
    units.into_iter().map(|(_, members)| members).collect()
}

fn routes_match(expected: &Armed, actual: &HttpRequest) -> bool {
    expected.method == actual.method && path_fits(&expected.path, &actual.path)
}

/// Every way `actual` differs from `expected` beyond its route.
fn check_against(expected: &Armed, actual: &HttpRequest) -> Vec<String> {
    let mut failures = Vec::new();
    for (name, matcher) in &expected.headers {
        if let Err(e) = header_matches(matcher, actual.header(name)) {
            failures.push(format!("header `{name}` {e}"));
        }
    }
    if !query_matches(expected, actual) {
        failures.push(format!("query {:?} is not {:?}", actual.query, expected.query));
    }
    if let Some(json) = &expected.json {
        match serde_json::from_slice::<Value>(&actual.body) {
            Ok(body) if body == *json => {}
            _ => failures.push(format!("JSON body {} is not {json}", String::from_utf8_lossy(&actual.body))),
        }
    }
    if let Some(form) = &expected.form
        && form_pairs(&actual.body).as_ref() != Ok(form)
    {
        failures.push(format!("form body {:?} is not {form:?}", String::from_utf8_lossy(&actual.body)));
    }
    failures
}

/// The query is the exact set the item names, as in the API clients' suite,
/// except on a `redirect` item: an authorize request carries values minted
/// per run (its loopback port, a PKCE challenge, a state), so there the
/// named parameters are a subset.
fn query_matches(expected: &Armed, actual: &HttpRequest) -> bool {
    let Ok(query) = actual.query_map() else { return false };
    if expected.response.redirect.is_some() {
        return expected.query.iter().all(|(k, v)| query.get(k) == Some(v));
    }
    query == expected.query
}

pub fn header_matches(matcher: &HeaderMatch, value: Option<&str>) -> Result<(), String> {
    let ok = match (matcher, value) {
        (HeaderMatch::Absent(true), v) => v.is_none(),
        (HeaderMatch::Absent(false), v) => v.is_some(),
        (_, None) => return Err("is missing".into()),
        (HeaderMatch::Equals(want), Some(v)) => v == want,
        (HeaderMatch::Prefix(want), Some(v)) => v.starts_with(want.as_str()),
        (HeaderMatch::Contains(want), Some(v)) => v.contains(want.as_str()),
        (HeaderMatch::Pattern(want), Some(v)) => Regex::new(want).is_ok_and(|re| re.is_match(v)),
    };
    if ok { Ok(()) } else { Err(format!("{value:?} does not satisfy {matcher:?}")) }
}
