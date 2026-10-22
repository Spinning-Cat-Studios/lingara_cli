//! cloudflared (ADR 16.10.26ae D8). **Lookup**: `LINGARA_CLOUDFLARED` and
//! then only that path, else `cloudflared` on `PATH`, refused below
//! `tools.toml`'s minimum. **URL**: the CLI picks a free loopback port for
//! `--metrics` and polls its `/quicktunnel`, falling back to the
//! `https://…trycloudflare.com` line on stderr. **Ready**: the probe URL
//! (`LINGARA_TUNNEL_PROBE_URL`, default `https://{hostname}/`) answers
//! anything but Cloudflare's `530`. Each wait is 30 s.

use std::collections::VecDeque;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::app::Env;
use crate::assets;
use crate::exit::{Exit, Fail};
use crate::supervise::Group;

pub const WAIT: Duration = Duration::from_secs(30);
const POLL: Duration = Duration::from_millis(250);
const KEPT_LINES: usize = 20;
const DEFAULT_PROBE: &str = "https://{hostname}/";

/// `tool_missing` with D8's hint, the exit row's own message.
pub fn missing() -> Fail {
    Fail::message(Exit::ToolMissing, "tool_missing_cloudflared", &[])
}

fn on_path(name: &str, env: &Env) -> Option<PathBuf> {
    let path = env.get("PATH")?;
    let exe = if cfg!(windows) { format!("{name}.exe") } else { name.to_string() };
    std::env::split_paths(path).map(|d| d.join(&exe)).find(|p| p.is_file())
}

/// The first `a.b.c` on `cloudflared --version`'s first line.
fn version_of(binary: &Path) -> Option<Vec<u64>> {
    let out = Command::new(binary).arg("--version").stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    let first = text.lines().next()?.to_string();
    first.split_whitespace().map(|w| w.split('.').map(|n| n.parse::<u64>()).collect::<Result<Vec<_>, _>>()).find_map(|v| v.ok().filter(|v| v.len() >= 2))
}

/// Lookup and version check, offline (D7 step 2).
pub fn find(env: &Env) -> Result<PathBuf, Fail> {
    let binary = match env.get("LINGARA_CLOUDFLARED") {
        Some(path) => Some(PathBuf::from(path)).filter(|p| p.is_file()),
        None => on_path("cloudflared", env),
    };
    let binary = binary.ok_or_else(missing)?;
    match version_of(&binary) {
        Some(v) if v >= assets::cloudflared_min_version() => Ok(binary),
        _ => Err(missing()),
    }
}

/// A free loopback port, for cloudflared's metrics server.
fn free_port() -> Result<u16, Fail> {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).map_err(|e| Fail::failure(format!("a free port: {e}")))?;
    listener.local_addr().map(|a| a.port()).map_err(|e| Fail::failure(e.to_string()))
}

/// The `https://…trycloudflare.com` hostname on one log line, if any.
fn hostname_in(line: &str) -> Option<String> {
    let start = line.find("https://")? + "https://".len();
    let host: String = line[start..].chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '.').collect();
    host.ends_with(".trycloudflare.com").then_some(host)
}

/// One cloudflared incarnation.
pub struct Tunnel {
    group: Group,
    metrics: String,
    lines: Arc<Mutex<VecDeque<String>>>,
    logged_host: Arc<Mutex<Option<String>>>,
    http: reqwest::blocking::Client,
}

impl Tunnel {
    /// `cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>`.
    pub fn spawn(binary: &Path, port: u16) -> Result<Tunnel, Fail> {
        let metrics = format!("127.0.0.1:{}", free_port()?);
        let mut command = Command::new(binary);
        command.args(["tunnel", "--url", &format!("http://127.0.0.1:{port}"), "--metrics", &metrics]);
        command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
        let mut group = Group::spawn(command).map_err(|e| Fail::new(Exit::TunnelFailed, "tunnel_failed", format!("cloudflared: {e}")))?;
        let (lines, logged_host) = (Arc::new(Mutex::new(VecDeque::new())), Arc::new(Mutex::new(None)));
        if let Some(stderr) = group.child.stderr.take() {
            let (lines, host) = (Arc::clone(&lines), Arc::clone(&logged_host));
            std::thread::spawn(move || keep(BufReader::new(stderr), &lines, &host));
        }
        let http = reqwest::blocking::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(2))
            .build()
            .map_err(|e| Fail::failure(e.to_string()))?;
        Ok(Tunnel { group, metrics, lines, logged_host, http })
    }

    pub fn alive(&mut self) -> bool {
        self.group.exited().is_none()
    }

    /// The last lines cloudflared wrote, for `tunnel_failed`'s reason.
    pub fn tail(&self) -> String {
        self.lines.lock().map(|l| l.iter().cloned().collect::<Vec<_>>().join("\n")).unwrap_or_default()
    }

    fn quicktunnel(&self) -> Option<String> {
        let body = self.http.get(format!("http://{}/quicktunnel", self.metrics)).send().ok()?.bytes().ok()?;
        let answer: serde_json::Value = serde_json::from_slice(&body).ok()?;
        answer["hostname"].as_str().filter(|h| !h.is_empty()).map(str::to_string)
    }

    /// The quick tunnel's hostname: metrics first, the log line second.
    pub fn hostname(&mut self, stop: &mut dyn FnMut() -> bool) -> Option<String> {
        let deadline = Instant::now() + WAIT;
        while Instant::now() < deadline && self.alive() && !stop() {
            let logged = self.logged_host.lock().ok().and_then(|h| h.clone());
            if let Some(host) = self.quicktunnel().or(logged) {
                return Some(host);
            }
            std::thread::sleep(POLL);
        }
        None
    }

    /// Whether the probe reached the app through the tunnel: any answer but
    /// a `530`, once a second, for at most 30 s.
    pub fn ready(&mut self, host: &str, template: Option<&str>, stop: &mut dyn FnMut() -> bool) -> bool {
        let url = template.unwrap_or(DEFAULT_PROBE).replace("{hostname}", host).replace("{metrics}", &self.metrics);
        let deadline = Instant::now() + WAIT;
        while Instant::now() < deadline && self.alive() && !stop() {
            if self.http.get(&url).send().is_ok_and(|r| r.status().as_u16() != 530) {
                return true;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
        false
    }

    pub fn stop(&mut self) {
        self.group.stop(Duration::from_secs(5));
    }
}

fn keep(reader: impl BufRead, lines: &Mutex<VecDeque<String>>, host: &Mutex<Option<String>>) {
    for line in reader.lines().map_while(Result::ok) {
        if let (Some(found), Ok(mut slot)) = (hostname_in(&line), host.lock()) {
            slot.get_or_insert(found);
        }
        if let Ok(mut kept) = lines.lock() {
            kept.push_back(line);
            while kept.len() > KEPT_LINES {
                kept.pop_front();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::hostname_in;

    #[test]
    fn the_log_line_names_the_quick_tunnel() {
        let line = "2024-01-01T00:00:00Z INF |  https://shy-owl-1.trycloudflare.com                                 |";
        assert_eq!(hostname_in(line).as_deref(), Some("shy-owl-1.trycloudflare.com"));
        assert_eq!(hostname_in("INF https://api.trycloudflare.com.example/"), None);
    }
}
