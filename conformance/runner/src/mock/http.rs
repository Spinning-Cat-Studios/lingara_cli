//! Just enough HTTP/1.1 for the mock, over a raw socket. Copied from the
//! API clients' conformance server (ADR 29.9.26n D12), not depended on, so
//! neither repository's release blocks the other's (ADR 16.10.26ac).
//!
//! One request per connection; every response says `connection: close`.

use std::collections::BTreeMap;
use std::io;

use tokio::io::{AsyncRead, AsyncReadExt};

const MAX_HEAD: usize = 64 * 1024;
const MAX_BODY: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, Default)]
pub struct HttpRequest {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    /// Names lowercased, in arrival order.
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpRequest {
    /// The first value of `name` (lowercase).
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }

    /// The query as a map; `Err` when a key repeats or does not decode.
    pub fn query_map(&self) -> Result<BTreeMap<String, String>, ()> {
        form_pairs(self.query.as_deref().unwrap_or("").as_bytes())
    }
}

/// Reads one request, or `None` when the peer closed before sending one.
pub async fn read_request<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<Option<HttpRequest>> {
    let mut buf = Vec::new();
    loop {
        let mut headers = [httparse::EMPTY_HEADER; 64];
        let mut parsed = httparse::Request::new(&mut headers);
        match parsed.parse(&buf).map_err(invalid)? {
            httparse::Status::Complete(len) => {
                let request = head_of(&parsed)?;
                let rest = buf.split_off(len);
                return read_body(r, request, rest).await.map(Some);
            }
            httparse::Status::Partial if buf.len() > MAX_HEAD => {
                return Err(invalid("request head too large"));
            }
            httparse::Status::Partial => {}
        }
        if !fill(r, &mut buf).await? {
            return if buf.is_empty() { Ok(None) } else { Err(eof()) };
        }
    }
}

fn head_of(parsed: &httparse::Request) -> io::Result<HttpRequest> {
    let target = parsed.path.ok_or_else(|| invalid("no request target"))?;
    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), Some(q.to_string())),
        None => (target.to_string(), None),
    };
    let headers = parsed
        .headers
        .iter()
        .map(|h| (h.name.to_ascii_lowercase(), String::from_utf8_lossy(h.value).into_owned()))
        .collect();
    let method = parsed.method.ok_or_else(|| invalid("no method"))?.to_string();
    Ok(HttpRequest { method, path, query, headers, body: Vec::new() })
}

/// A fixed-length body. A CLI sends no chunked request body, so the copy
/// drops the original's chunked branch.
async fn read_body<R: AsyncRead + Unpin>(
    r: &mut R,
    mut request: HttpRequest,
    mut buf: Vec<u8>,
) -> io::Result<HttpRequest> {
    let length = match request.header("content-length") {
        Some(v) => v.trim().parse::<usize>().map_err(invalid)?,
        None => 0,
    };
    if length > MAX_BODY {
        return Err(invalid("request body too large"));
    }
    while buf.len() < length {
        if !fill(r, &mut buf).await? {
            return Err(eof());
        }
    }
    buf.truncate(length);
    request.body = buf;
    Ok(request)
}

async fn fill<R: AsyncRead + Unpin>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<bool> {
    let mut chunk = [0u8; 8192];
    let n = r.read(&mut chunk).await?;
    buf.extend_from_slice(&chunk[..n]);
    Ok(n > 0)
}

/// The status line and headers, ending in the blank line.
pub fn head(status: u16, headers: &[(String, String)]) -> Vec<u8> {
    let mut out = format!("HTTP/1.1 {status} {}\r\n", reason(status));
    for (name, value) in headers {
        out.push_str(&format!("{name}: {value}\r\n"));
    }
    out.push_str("connection: close\r\n\r\n");
    out.into_bytes()
}

/// A whole response with a fixed-length body.
pub fn whole(status: u16, content_type: &str, body: &[u8]) -> Vec<u8> {
    let headers = [
        ("content-type".to_string(), content_type.to_string()),
        ("content-length".to_string(), body.len().to_string()),
    ];
    let mut out = head(status, &headers);
    out.extend_from_slice(body);
    out
}

const REASONS: &[(u16, &str)] = &[
    (200, "OK"),
    (201, "Created"),
    (204, "No Content"),
    (302, "Found"),
    (400, "Bad Request"),
    (401, "Unauthorized"),
    (403, "Forbidden"),
    (404, "Not Found"),
    (409, "Conflict"),
    (422, "Unprocessable Content"),
    (429, "Too Many Requests"),
    (500, "Internal Server Error"),
    (503, "Service Unavailable"),
    (599, "Conformance Mismatch"),
];

fn reason(status: u16) -> &'static str {
    REASONS.iter().find(|(s, _)| *s == status).map_or("Status", |(_, r)| r)
}

/// `application/x-www-form-urlencoded` decoding: `+` is a space, then
/// percent-decoding.
pub fn form_decode(s: &str) -> Option<String> {
    let bytes = s.replace('+', " ").into_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// Percent-encodes everything but RFC 3986's unreserved characters.
pub fn form_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// A form body or a query as a map; a repeated key is refused.
pub fn form_pairs(body: &[u8]) -> Result<BTreeMap<String, String>, ()> {
    let text = std::str::from_utf8(body).map_err(|_| ())?;
    let mut out = BTreeMap::new();
    for pair in text.split('&').filter(|p| !p.is_empty()) {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        let (k, v) = (form_decode(k).ok_or(())?, form_decode(v).ok_or(())?);
        if out.insert(k, v).is_some() {
            return Err(());
        }
    }
    Ok(out)
}

fn invalid<E: ToString>(e: E) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, e.to_string())
}

fn eof() -> io::Error {
    io::Error::new(io::ErrorKind::UnexpectedEof, "connection closed mid-request")
}
