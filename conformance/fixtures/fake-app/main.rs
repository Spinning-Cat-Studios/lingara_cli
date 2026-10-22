//! `fake-app` (ADR 16.10.26ae D11): what a project fixture's `start` runs in
//! an `apps` case. It records its environment's variable **names**, and a
//! SHA-256 of each `LINGARA_*` value, to `$LINGARA_CONFORMANCE_CHILD_ENV`;
//! writes its pid under the case's directory for the orphan check; listens
//! on `$PORT`, answering a POST with a fixed card and anything else `404`;
//! and exits on the schedule in `$LINGARA_FAKE_APP`
//! (`{dir, exit_after_ms?, status, listen}`).

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

fn record() {
    let Ok(path) = std::env::var("LINGARA_CONFORMANCE_CHILD_ENV") else { return };
    let mut names: Vec<String> = std::env::vars().map(|(k, _)| k).collect();
    names.sort();
    let hash = |v: &str| Sha256::digest(v.as_bytes()).iter().map(|b| format!("{b:02x}")).collect::<String>();
    let hashes: serde_json::Map<String, Value> =
        std::env::vars().filter(|(k, _)| k.starts_with("LINGARA_")).map(|(k, v)| (k, json!(hash(&v)))).collect();
    let _ = std::fs::write(path, json!({ "names": names, "hashes": hashes }).to_string());
}

fn pid_file(schedule: &Value) {
    let Some(dir) = schedule["dir"].as_str() else { return };
    let pids = std::path::Path::new(dir).join("pids");
    let _ = std::fs::create_dir_all(&pids);
    let _ = std::fs::write(pids.join(format!("app-{}", std::process::id())), "");
}

fn answer(mut stream: TcpStream) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let mut buf = [0u8; 8192];
    let n = stream.read(&mut buf).unwrap_or(0);
    let head = String::from_utf8_lossy(&buf[..n]).to_string();
    let (status, body) = if head.starts_with("POST") {
        (200, json!({ "card": { "blocks": [{ "type": "heading", "text": "fake", "level": 1 }] } }).to_string())
    } else {
        (404, json!({ "error": "not_found" }).to_string())
    };
    let reply = format!("HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len());
    let _ = stream.write_all(reply.as_bytes());
}

fn main() {
    let schedule: Value = std::env::var("LINGARA_FAKE_APP").ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(json!({}));
    pid_file(&schedule);
    record();
    if schedule["listen"] != false {
        let port = std::env::var("PORT").ok().and_then(|p| p.parse::<u16>().ok()).unwrap_or(8787);
        let listener = TcpListener::bind(("127.0.0.1", port)).expect("the app's port is free");
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                std::thread::spawn(move || answer(stream));
            }
        });
    }
    println!("fake app up");
    match schedule["exit_after_ms"].as_u64() {
        Some(ms) => {
            std::thread::sleep(Duration::from_millis(ms));
            std::process::exit(schedule["status"].as_i64().unwrap_or(0) as i32);
        }
        None => loop {
            std::thread::sleep(Duration::from_secs(3600));
        },
    }
}
