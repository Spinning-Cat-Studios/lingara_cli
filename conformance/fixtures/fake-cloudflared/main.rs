//! `fake-cloudflared` (ADR 16.10.26ae D11): enough of a quick tunnel for an
//! `apps dev` case. `--version` reports `spec/tools.toml`'s floor.
//! `tunnel --url <url> --metrics <addr>` counts its incarnation in a file
//! under the case's directory, writes its pid for the orphan check, waits
//! `ready_after_ms`, prints cloudflared's banner on stderr, and serves on the
//! metrics address: `GET /quicktunnel` → `{"hostname":
//! "conformance-<k>.trycloudflare.com"}`, and `/forward/*`, which answers
//! `530` for its first `answer_530_first` probes and then the app's own
//! status. Incarnation k exits after `die_after_ms[k-1]`, else lives. The
//! schedule is `$LINGARA_FAKE_CLOUDFLARED`.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde_json::{Value, json};

static PROBES: AtomicU64 = AtomicU64::new(0);

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned()
}

/// This incarnation's number: one more than those before it.
fn incarnation(dir: &str) -> u64 {
    let counted = std::path::Path::new(dir).join("cloudflared");
    let _ = std::fs::create_dir_all(&counted);
    let k = std::fs::read_dir(&counted).map(|d| d.count() as u64).unwrap_or(0) + 1;
    let _ = std::fs::write(counted.join(format!("incarnation-{k}")), "");
    let pids = std::path::Path::new(dir).join("pids");
    let _ = std::fs::create_dir_all(&pids);
    let _ = std::fs::write(pids.join(format!("cloudflared-{}", std::process::id())), "");
    k
}

/// The app's status for `GET /`, or `502` when it does not answer.
fn forward(app: &str) -> u16 {
    let address = app.trim_start_matches("http://").trim_end_matches('/');
    let Ok(mut stream) = TcpStream::connect(address) else { return 502 };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.write_all(b"GET / HTTP/1.0\r\n\r\n");
    let mut buf = [0u8; 64];
    let n = stream.read(&mut buf).unwrap_or(0);
    let line = String::from_utf8_lossy(&buf[..n]).to_string();
    line.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(502)
}

fn serve(mut stream: TcpStream, host: &str, app: &str, first_530: u64) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let mut buf = [0u8; 4096];
    let n = stream.read(&mut buf).unwrap_or(0);
    let head = String::from_utf8_lossy(&buf[..n]).to_string();
    let path = head.split_whitespace().nth(1).unwrap_or("/").to_string();
    let (status, body) = if path == "/quicktunnel" {
        (200, json!({ "hostname": host }).to_string())
    } else if path.starts_with("/forward/") && PROBES.fetch_add(1, Ordering::SeqCst) < first_530 {
        (530, "error code: 1033".to_string())
    } else if path.starts_with("/forward/") {
        (forward(app), String::new())
    } else {
        (404, String::new())
    };
    let reply = format!("HTTP/1.1 {status} X\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len());
    let _ = stream.write_all(reply.as_bytes());
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let schedule: Value = std::env::var("LINGARA_FAKE_CLOUDFLARED").ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(json!({}));
    if args.iter().any(|a| a == "--version") {
        println!("cloudflared version {} (conformance)", schedule["version"].as_str().unwrap_or("2023.4.1"));
        return;
    }
    let (Some(app), Some(metrics)) = (flag(&args, "--url"), flag(&args, "--metrics")) else {
        eprintln!("fake-cloudflared: tunnel --url <url> --metrics <addr>");
        std::process::exit(2);
    };
    let k = incarnation(schedule["dir"].as_str().unwrap_or("."));
    let host = format!("conformance-{k}.trycloudflare.com");
    std::thread::sleep(Duration::from_millis(schedule["ready_after_ms"].as_u64().unwrap_or(0)));
    eprintln!("INF Requesting new quick Tunnel on trycloudflare.com...");
    eprintln!("INF |  https://{host}  |");
    let listener = TcpListener::bind(&metrics).expect("the metrics address is free");
    let first_530 = schedule["answer_530_first"].as_u64().unwrap_or(0);
    let served = host.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let (host, app) = (served.clone(), app.clone());
            std::thread::spawn(move || serve(stream, &host, &app, first_530));
        }
    });
    match schedule["die_after_ms"].as_array().and_then(|d| d.get(k as usize - 1)).and_then(Value::as_u64) {
        Some(ms) => {
            std::thread::sleep(Duration::from_millis(ms));
            eprintln!("ERR the fake tunnel died on schedule");
            std::process::exit(1);
        }
        None => loop {
            std::thread::sleep(Duration::from_secs(3600));
        },
    }
}
