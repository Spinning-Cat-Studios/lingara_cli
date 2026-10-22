//! The world an `apps` case runs in (ADR 16.10.26ae D11): its project, its
//! fakes and their environment, and the orphan check after it. The two
//! fakes are this crate's own binaries, found beside the runner's:
//! `make conformance-<lang>` builds them first.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::json;

use crate::case::Case;

/// What `{free_port}` in a project's `platform.toml` becomes, and where the
/// CLI runs.
pub struct Setup {
    pub cwd: PathBuf,
    pub env: BTreeMap<String, String>,
}

/// A fake binary beside the runner's own executable.
fn fake(name: &str) -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_default();
    exe.parent().map_or_else(|| PathBuf::from(name), |d| d.join(name))
}

fn free_port() -> u16 {
    std::net::TcpListener::bind(("127.0.0.1", 0)).and_then(|l| l.local_addr()).map_or(47801, |a| a.port())
}

fn copy_tree(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)?.flatten() {
        let (src, dst) = (entry.path(), to.join(entry.file_name()));
        if src.is_dir() { copy_tree(&src, &dst)? } else { std::fs::copy(&src, &dst).map(|_| ())? }
    }
    Ok(())
}

/// `project`: the fixture copied into `home`, `{free_port}` filled.
fn project(case: &Case, home: &Path, fixtures: &Path, env: &mut BTreeMap<String, String>) -> Result<PathBuf, String> {
    let Some(project) = &case.project else { return Ok(home.to_path_buf()) };
    let (from, to) = (fixtures.join("projects").join(project.slug()), home.join(project.slug()));
    copy_tree(&from, &to).map_err(|e| format!("project {}: {e}", from.display()))?;
    let toml = to.join("platform.toml");
    if let Ok(text) = std::fs::read_to_string(&toml) {
        std::fs::write(&toml, text.replace("{free_port}", &free_port().to_string())).map_err(|e| e.to_string())?;
    }
    let app = project.app();
    let schedule = json!({ "dir": home, "exit_after_ms": app.exit_after_ms, "status": app.status, "listen": app.listen.unwrap_or(true) });
    env.insert("LINGARA_CONFORMANCE_FAKE_APP".into(), fake("fake-app").display().to_string());
    env.insert("LINGARA_FAKE_APP".into(), schedule.to_string());
    env.insert("LINGARA_CONFORMANCE_CHILD_ENV".into(), home.join("child-env.json").display().to_string());
    Ok(to)
}

/// `tunnel`: the fake and its schedule, or a path that does not exist.
fn tunnel(case: &Case, home: &Path, min_version: &str, env: &mut BTreeMap<String, String>) {
    let Some(spec) = &case.tunnel else { return };
    let Some(s) = spec.schedule() else {
        env.insert("LINGARA_CLOUDFLARED".into(), home.join("no-such-cloudflared").display().to_string());
        return;
    };
    let schedule = json!({
        "dir": home, "version": min_version, "die_after_ms": s.die_after_ms,
        "ready_after_ms": s.ready_after_ms, "answer_530_first": s.answer_530_first,
    });
    env.insert("LINGARA_CLOUDFLARED".into(), fake("fake-cloudflared").display().to_string());
    env.insert("LINGARA_TUNNEL_PROBE_URL".into(), "http://{metrics}/forward/".into());
    env.insert("LINGARA_FAKE_CLOUDFLARED".into(), schedule.to_string());
}

/// The cwd and the extra environment for `case`, after copying its project.
pub fn setup(case: &Case, home: &Path, fixtures: &Path, min_version: &str) -> Result<Setup, String> {
    let mut env = BTreeMap::new();
    let cwd = project(case, home, fixtures, &mut env)?;
    tunnel(case, home, min_version, &mut env);
    Ok(Setup { cwd, env })
}

/// `spec/tools.toml`'s cloudflared floor, which the fake reports as its version.
pub fn min_version(schema: &Path) -> String {
    let text = std::fs::read_to_string(schema.with_file_name("tools.toml")).unwrap_or_default();
    let value: toml::Value = toml::from_str(&text).unwrap_or(toml::Value::Table(Default::default()));
    value.get("cloudflared").and_then(|c| c.get("min_version")).and_then(|v| v.as_str()).unwrap_or("2023.4.1").to_string()
}

/// Every fake that outlived the case: killed, and named. Each fake writes
/// `pids/<kind>-<pid>` into the case's directory when it starts.
pub fn orphans(home: &Path) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(home.join("pids")) else { return Vec::new() };
    std::thread::sleep(std::time::Duration::from_millis(300));
    let mut out = Vec::new();
    for name in entries.flatten().map(|e| e.file_name().to_string_lossy().to_string()) {
        let Some(pid) = name.rsplit('-').next().and_then(|p| p.parse::<i32>().ok()) else { continue };
        // SAFETY: signal 0 only asks whether the process exists; SIGKILL ends an orphan.
        if unsafe { nix::libc::kill(pid, 0) } == 0 {
            unsafe { nix::libc::kill(pid, nix::libc::SIGKILL) };
            out.push(format!("orphan: {name} outlived the CLI"));
        }
    }
    out
}
