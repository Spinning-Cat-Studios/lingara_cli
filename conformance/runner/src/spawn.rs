//! The world a kit runs in (ADR 16.10.26ac D5 steps 2–4): an environment
//! built from scratch, the credential seed, and the child itself with pipes
//! or a pseudo-terminal (`spawn/child.rs`).

pub mod apps;
mod browser;
mod child;

pub use browser::BROWSER_AGENT;
pub use child::{Job, Outcome, SignalPlan, Step, run};

use std::collections::BTreeMap;
use std::path::Path;
use std::time::Duration;

use crate::case::{Case, Credentials};

/// How long an `interact.expect` may take to appear (README.md).
pub const INTERACT_TIMEOUT: Duration = Duration::from_secs(5);
/// How long a case may run before it is killed and failed (README.md).
pub const EXIT_TIMEOUT: Duration = Duration::from_secs(30);

/// The CI client a `credentials: ci` case signs in as (D3's fake values).
pub const CI_CLIENT_ID: &str = "lgr_cid_conformance_ci";
pub const CI_CLIENT_SECRET: &str = "lgr_cs_conformance_ci";

/// Every `LINGARA_*` name the runner itself sets or reads, which a case's
/// `env` may never set.
pub const RUNNER_ENV: &[&str] = &[
    "LINGARA_CONFIG_DIR",
    "LINGARA_API_URL",
    "LINGARA_PLATFORM_URL",
    "LINGARA_KEYRING",
    "LINGARA_NO_BROWSER",
    "LINGARA_PLATFORM_CLIENT_ID",
    "LINGARA_PLATFORM_CLIENT_SECRET",
    "LINGARA_CONFORMANCE_OUT",
    "LINGARA_CONFORMANCE_ONLY",
    // ADR 16.10.26ae D11: the `apps` group's fakes.
    "LINGARA_CLOUDFLARED",
    "LINGARA_TUNNEL_PROBE_URL",
    "LINGARA_FAKE_CLOUDFLARED",
    "LINGARA_CONFORMANCE_FAKE_APP",
    "LINGARA_FAKE_APP",
    "LINGARA_CONFORMANCE_CHILD_ENV",
];

#[derive(Debug, Clone, Copy)]
pub struct Limits {
    pub interact: Duration,
    pub exit: Duration,
}

impl Default for Limits {
    fn default() -> Self {
        Limits { interact: INTERACT_TIMEOUT, exit: EXIT_TIMEOUT }
    }
}

/// Refuses a `--pass-env` name that would let a kit change the environment
/// conformance judges it in.
pub fn check_pass_env(names: &[String]) -> Result<(), String> {
    for name in names {
        let judged = matches!(name.as_str(), "HOME" | "CI" | "NO_COLOR" | "TERM" | "LANG")
            || name.starts_with("LC_")
            || name.starts_with("LINGARA_");
        if judged {
            return Err(format!("--pass-env {name}: the runner sets or drops that name itself"));
        }
    }
    Ok(())
}

/// The child's whole environment: `PATH` and the pass-through names from
/// `parent`, then D5's variables, then the case's `env`. Nothing else, so
/// `CI` and any stray `LINGARA_*` never reach the kit.
pub fn build_env(
    parent: &BTreeMap<String, String>,
    pass: &[String],
    home: &Path,
    mock_url: &str,
    case: &Case,
) -> BTreeMap<String, String> {
    let mut env = BTreeMap::new();
    for name in std::iter::once("PATH").chain(pass.iter().map(String::as_str)) {
        if let Some(v) = parent.get(name) {
            env.insert(name.to_string(), v.clone());
        }
    }
    let home = home.display().to_string();
    let fixed = [
        ("HOME", home.as_str()),
        ("LINGARA_CONFIG_DIR", home.as_str()),
        ("LINGARA_API_URL", mock_url),
        ("LINGARA_PLATFORM_URL", mock_url),
        ("LINGARA_KEYRING", "file"),
        ("LINGARA_NO_BROWSER", "1"),
        ("NO_COLOR", "1"),
        ("LANG", "en_US.UTF-8"),
        ("TERM", "dumb"),
    ];
    env.extend(fixed.iter().map(|(k, v)| (k.to_string(), v.to_string())));
    if case.credentials == Credentials::Ci {
        env.insert("LINGARA_PLATFORM_CLIENT_ID".into(), CI_CLIENT_ID.into());
        env.insert("LINGARA_PLATFORM_CLIENT_SECRET".into(), CI_CLIENT_SECRET.into());
    }
    env.extend(case.env.clone());
    env
}

/// The ids `LINGARA_CONFORMANCE_ONLY` narrows a run to, refused under CI.
pub fn only_filter(only: Option<&str>, ci: Option<&str>) -> Result<Option<Vec<String>>, String> {
    let Some(only) = only.filter(|o| !o.trim().is_empty()) else { return Ok(None) };
    if ci.is_some_and(|c| !c.is_empty()) {
        return Err("LINGARA_CONFORMANCE_ONLY is refused when CI is set: a CI run runs every case".into());
    }
    Ok(Some(only.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()))
}

/// `stored` copies the fixture to `credentials.json`, mode `0600` in a
/// `0700` directory; `ci` and `none` write nothing (D5 step 3).
pub fn seed(case: &Case, home: &Path, fixture: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(home, std::fs::Permissions::from_mode(0o700))?;
    if case.credentials != Credentials::Stored {
        return Ok(());
    }
    let target = home.join("credentials.json");
    std::fs::copy(fixture, &target)?;
    std::fs::set_permissions(&target, std::fs::Permissions::from_mode(0o600))
}
