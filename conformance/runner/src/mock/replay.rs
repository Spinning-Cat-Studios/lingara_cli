//! Scripted responses: the API clients' head and whole-body answers (ADR
//! 29.9.26n D10, without its streams), plus D3's `redirect`.

use std::time::Duration;

use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;

use super::http::{self, HttpRequest, form_encode};
use crate::case::Response;

/// The status line and headers, with a JSON or text `content-type` default
/// and a `content-length` on every body (never on a `204`, RFC 9110 §8.6).
pub fn head_for(response: &Response, request: &HttpRequest) -> Vec<u8> {
    let mut headers: Vec<(String, String)> = response
        .headers
        .iter()
        .flatten()
        .map(|(k, v)| (k.clone(), v.as_str().map_or_else(|| v.to_string(), str::to_string)))
        .collect();
    let has = |headers: &[(String, String)], name: &str| headers.iter().any(|(k, _)| k.eq_ignore_ascii_case(name));
    let default_type = match (&response.json, &response.text) {
        (Some(_), _) => Some("application/json"),
        (_, Some(_)) => Some("text/plain; charset=utf-8"),
        _ => None,
    };
    if let Some(ct) = default_type.filter(|_| !has(&headers, "content-type")) {
        headers.push(("content-type".into(), ct.into()));
    }
    if let Some(location) = location(response, request) {
        headers.push(("location".into(), location));
    }
    if response.status() != 204 {
        headers.push(("content-length".into(), body_for(response).len().to_string()));
    }
    http::head(response.status(), &headers)
}

/// The redirect target: the request's `to_query` parameter with `params`
/// and the echoed parameters appended. `None` without a `redirect`, or when
/// the request carried no such parameter (the kit's mismatch to own).
pub fn location(response: &Response, request: &HttpRequest) -> Option<String> {
    let redirect = response.redirect.as_ref()?;
    let query = request.query_map().ok()?;
    let target = query.get(&redirect.to_query)?;
    let echoed = redirect.echo.iter().filter_map(|k| query.get(k).map(|v| (k, v)));
    let pairs: Vec<String> =
        redirect.params.iter().chain(echoed).map(|(k, v)| format!("{}={}", form_encode(k), form_encode(v))).collect();
    let joiner = if target.contains('?') { '&' } else { '?' };
    Some(format!("{target}{joiner}{}", pairs.join("&")))
}

/// A body: the JSON, the text, or nothing.
pub fn body_for(response: &Response) -> Vec<u8> {
    match (&response.json, &response.text) {
        (Some(json), _) => serde_json::to_vec(json).unwrap_or_default(),
        (_, Some(text)) => text.clone().into_bytes(),
        _ => Vec::new(),
    }
}

/// Answers one matched request on its own connection.
pub async fn respond(mut stream: TcpStream, response: &Response, request: &HttpRequest) -> std::io::Result<()> {
    if let Some(ms) = response.delay_ms {
        tokio::time::sleep(Duration::from_millis(ms)).await;
    }
    stream.write_all(&head_for(response, request)).await?;
    stream.write_all(&body_for(response)).await?;
    stream.flush().await?;
    stream.shutdown().await
}
