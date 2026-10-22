//! `login`'s default: PKCE over a loopback redirect (RFC 8252, 16.10.26u
//! §6). The kit binds `127.0.0.1:0`, opens the portal's authorize page,
//! and accepts exactly one callback whose `state` matches and whose `iss`
//! is the portal base (RFC 9207). The listener gives up after 5 minutes.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::RngCore;
use serde_json::Value;
use sha2::{Digest, Sha256};

use super::SCOPES;
use crate::app::Ctx;
use crate::assets::message;
use crate::exit::{Exit, Fail};
use crate::http::Auth;
use crate::http::routes::{Fixed, fixed};

const LISTEN_FOR: Duration = Duration::from_secs(300);
const CLOSE_TAB: &str = "<!doctype html><title>Lingara</title><p>You can close this tab.</p>";

fn random_text() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

/// What the browser brought back to the loopback.
enum Callback {
    Code(String),
    Declined,
}

impl Ctx<'_> {
    /// The token response for the code the browser brought back.
    pub fn browser_login(&mut self) -> Result<Value, Fail> {
        let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| Fail::failure(format!("could not listen on 127.0.0.1: {e}")))?;
        let port = listener.local_addr().map_err(|e| Fail::failure(e.to_string()))?.port();
        let (verifier, state) = (random_text(), random_text());
        let redirect = format!("http://127.0.0.1:{port}/callback");
        let client_id = self.app.endpoints.client_id.clone();
        let code_challenge = challenge(&verifier);
        let query = [
            ("response_type", "code"),
            ("client_id", client_id.as_str()),
            ("redirect_uri", redirect.as_str()),
            ("code_challenge", code_challenge.as_str()),
            ("code_challenge_method", "S256"),
            ("state", state.as_str()),
            ("scope", SCOPES),
        ];
        let url = self.app.http.url(&fixed(Fixed::Authorize), &[], &query);
        self.out.note(&url);
        self.out.note(&message("login_browser_wait", &[]));
        (self.app.browser)(&url);
        let issuer = self.app.http.bases.portal.trim_end_matches('/').to_string();
        let Callback::Code(code) = wait_for_callback(&listener, &state, &issuer)? else {
            return Err(Fail::new(Exit::AuthRequired, "access_denied", "sign-in was declined"));
        };
        let form = [("grant_type", "authorization_code"), ("code", &code), ("redirect_uri", &redirect), ("code_verifier", &verifier), ("client_id", &client_id)];
        self.token_post(Fixed::Token, form.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(), &Auth::None)
    }
}

fn wait_for_callback(listener: &TcpListener, state: &str, issuer: &str) -> Result<Callback, Fail> {
    listener.set_nonblocking(true).map_err(|e| Fail::failure(e.to_string()))?;
    let deadline = Instant::now() + LISTEN_FOR;
    while Instant::now() < deadline {
        match listener.accept() {
            Ok((stream, _)) => {
                if let Some(callback) = answer(stream, state, issuer) {
                    return Ok(callback);
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(20)),
            Err(e) => return Err(Fail::failure(format!("the loopback listener failed: {e}"))),
        }
    }
    Err(Fail::auth("no sign-in arrived within 5 minutes; run `lingara login` again"))
}

/// Answers one request on the loopback; the callback, if it is the one.
fn answer(mut stream: TcpStream, state: &str, issuer: &str) -> Option<Callback> {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut buf = [0u8; 8192];
    let n = stream.read(&mut buf).unwrap_or(0);
    let head = String::from_utf8_lossy(&buf[..n]);
    let target = head.split_whitespace().nth(1).unwrap_or("");
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let params = parse_query(query);
    let param = |k: &str| params.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str());
    let ours = path == "/callback" && param("state") == Some(state) && param("iss").map(|i| i.trim_end_matches('/')) == Some(issuer);
    let status = if ours { "200 OK" } else { "400 Bad Request" };
    let reply = format!("HTTP/1.1 {status}\r\ncontent-type: text/html\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{CLOSE_TAB}", CLOSE_TAB.len());
    let _ = stream.write_all(reply.as_bytes());
    let _ = stream.flush();
    if !ours {
        return None;
    }
    Some(param("code").map_or(Callback::Declined, |c| Callback::Code(c.to_string())))
}

fn parse_query(query: &str) -> Vec<(String, String)> {
    query.split('&').filter(|p| !p.is_empty()).map(|p| p.split_once('=').unwrap_or((p, ""))).map(|(k, v)| (decode(k), decode(v))).collect()
}

/// `application/x-www-form-urlencoded` decoding: `+` is a space, `%XX` a byte.
fn decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = bytes.get(i + 1..i + 3).and_then(|h| std::str::from_utf8(h).ok()).and_then(|h| u8::from_str_radix(h, 16).ok());
        match (bytes[i], hex) {
            (b'%', Some(b)) => {
                out.push(b);
                i += 3;
            }
            (b'+', _) => {
                out.push(b' ');
                i += 1;
            }
            (b, _) => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn s256_and_decoding() {
        // RFC 7636 appendix B.
        assert_eq!(challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assert_eq!(decode("http%3A%2F%2F127.0.0.1%3A80+x"), "http://127.0.0.1:80 x");
        assert_eq!(parse_query("a=1&b=%2F"), vec![("a".into(), "1".into()), ("b".into(), "/".into())]);
    }
}
