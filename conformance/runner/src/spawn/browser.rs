//! `browser: follow_url` (D3, D5 step 4): what a browser does with the URL
//! a login prints — GET it, then follow each redirect, ending at the kit's
//! own loopback listener.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::LazyLock;
use std::time::Duration;

use regex::Regex;

/// The browser's own `User-Agent`: its requests are the runner's, not the
/// kit's, so I3 does not judge them.
pub const BROWSER_AGENT: &str = "lingara-conformance-browser";

const MAX_HOPS: usize = 5;
const IO_TIMEOUT: Duration = Duration::from_secs(5);

static URL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"https?://[^\s"'<>]+"#).expect("the URL pattern compiles"));

/// The first http(s) URL in `text`.
pub fn first_url(text: &str) -> Option<String> {
    URL.find(text).map(|m| m.as_str().to_string())
}

/// GETs `url` and follows up to five redirects; the final status.
pub fn follow(url: &str) -> Result<u16, String> {
    let mut url = url.to_string();
    for _ in 0..=MAX_HOPS {
        let (status, location) = get(&url)?;
        match location.filter(|_| (300..400).contains(&status)) {
            Some(next) => url = next,
            None => return Ok(status),
        }
    }
    Err(format!("browser: more than {MAX_HOPS} redirects"))
}

/// One GET: the status and any `Location`.
fn get(url: &str) -> Result<(u16, Option<String>), String> {
    let rest = url.strip_prefix("http://").ok_or_else(|| format!("browser: {url} is not plain http"))?;
    let (host, target) = rest.split_at(rest.find('/').unwrap_or(rest.len()));
    let target = if target.is_empty() { "/" } else { target };
    let mut stream = TcpStream::connect(host).map_err(|e| format!("browser: {url}: {e}"))?;
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let request = format!("GET {target} HTTP/1.1\r\nhost: {host}\r\nuser-agent: {BROWSER_AGENT}\r\nconnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|e| format!("browser: {url}: {e}"))?;
    let mut answer = Vec::new();
    let _ = stream.read_to_end(&mut answer);
    let text = String::from_utf8_lossy(&answer);
    let head = text.split("\r\n\r\n").next().unwrap_or("");
    let status = head
        .split(' ')
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("browser: {url} answered no HTTP status"))?;
    let location = head.lines().find_map(|l| {
        let (name, value) = l.split_once(':')?;
        name.eq_ignore_ascii_case("location").then(|| value.trim().to_string())
    });
    Ok((status, location))
}
