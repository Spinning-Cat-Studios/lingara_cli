//! An `apps dev` session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it, start
//! the app with the session's credentials, hand over to `dev_hold`, then end
//! in order — `DELETE` the session, stop cloudflared, stop the app's group —
//! each step running even if the one before it failed. Under `--json`,
//! stdout carries one value, written here at exit.

use std::path::Path;
use std::process::{ExitStatus, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use serde_json::{Value, json};

use super::dev::Plan;
use super::dev_api::{self, Session};
use super::dev_hold;
use crate::app::Ctx;
use crate::assets::message;
use crate::commands::objects::{self, APP};
use crate::exit::{Exit, Fail};
use crate::supervise::{self, Group};
use crate::tunnel::cloudflared::Tunnel;

const LISTEN_WAIT: Duration = Duration::from_secs(60);
const STOP_GRACE: Duration = Duration::from_secs(5);
const APP_PREFIX: &str = "app │ ";

/// The children and what the session pointed at, for step 8 and the report.
#[derive(Default)]
pub struct Run {
    pub app: Option<Group>,
    pub tunnel: Option<Tunnel>,
    pub urls: Vec<String>,
    /// The session ended under us (taken over, expired): no `DELETE`.
    pub lost: bool,
    pub respawns: usize,
    /// Ctrl+C or SIGTERM arrived.
    pub stop: Arc<AtomicBool>,
}

impl Run {
    pub fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    fn stop_children(&mut self) {
        if let Some(tunnel) = self.tunnel.as_mut() {
            tunnel.stop();
        }
        if let Some(app) = self.app.as_mut() {
            app.stop(STOP_GRACE);
        }
    }
}

/// Ctrl+C and SIGTERM, as a flag the session polls.
fn signals() -> Arc<AtomicBool> {
    let flag = Arc::new(AtomicBool::new(false));
    for signal in [signal_hook::consts::SIGINT, signal_hook::consts::SIGTERM] {
        let _ = signal_hook::flag::register(signal, Arc::clone(&flag));
    }
    flag
}

pub fn interrupted() -> Fail {
    Fail::new(Exit::Interrupted, "interrupted", "interrupted")
}

pub fn lost(name: &str) -> Fail {
    Fail::message(Exit::DevSessionLost, "dev_session_lost", &[("name", name)])
}

/// `409 dev_session_held` re-worded with the app's name and the lease.
fn held(fail: Fail, name: &str) -> Fail {
    if fail.exit != Exit::DevSessionHeld {
        return fail;
    }
    let body: Value = serde_json::from_str(fail.body.as_deref().unwrap_or("")).unwrap_or_default();
    let lease = body["lease_expires_at"].as_str().unwrap_or("its lease ends").to_string();
    Fail::message(Exit::DevSessionHeld, "dev_session_held", &[("name", name), ("lease_expires_at", &lease)])
}

pub fn session(ctx: &mut Ctx, plan: &Plan) -> Result<(), Fail> {
    let stop = signals();
    let client_id = match plan.client {
        true => objects::get(ctx, &APP, &plan.id)?["client_id"].as_str().map(str::to_string),
        false => None,
    };
    let session = dev_api::open(ctx, &plan.id, plan.take_over, plan.client).map_err(|f| held(f, &plan.name))?;
    let mut run = Run { stop, ..Run::default() };
    let result = live(ctx, plan, &session, client_id.as_deref(), &mut run);
    if !run.lost {
        let _ = dev_api::end(ctx, &plan.id, &session.id);
    }
    run.stop_children();
    if ctx.out.json {
        let exit = result.as_ref().err().map_or(Exit::Ok, |f| f.exit);
        let report = json!({ "name": plan.name, "app_id": plan.id, "session_id": session.id, "urls": run.urls, "exit": exit.name() });
        ctx.out.json_value(&report);
    }
    result
}

fn live(ctx: &mut Ctx, plan: &Plan, session: &Session, client_id: Option<&str>, run: &mut Run) -> Result<(), Fail> {
    let extra = session_vars(plan, session, client_id);
    announce(ctx, plan, session, &extra)?;
    let env = supervise::child_env(&ctx.app.env.0, &extra);
    run.app = Some(start_app(plan, &env)?);
    if !wait_app(plan, run)? {
        return Ok(());
    }
    dev_hold::first_url(ctx, plan, session, run)?;
    dev_hold::hold(ctx, plan, session, run)
}

/// What the app's environment gains (D5, D7 step 4): the row's `env` and
/// `env_file`, the session's credentials, and the port.
fn session_vars(plan: &Plan, session: &Session, client_id: Option<&str>) -> Vec<(String, String)> {
    let mut vars = plan.env.clone();
    vars.push(("LINGARA_APP_SECRET".into(), session.app_secret.clone()));
    if let (Some(id), Some(secret)) = (client_id, &session.client_secret) {
        vars.push(("LINGARA_CLIENT_ID".into(), id.to_string()));
        vars.push(("LINGARA_CLIENT_SECRET".into(), secret.clone()));
    }
    vars.push(("PORT".into(), plan.port.to_string()));
    vars.push(("LINGARA_APP_PORT".into(), plan.port.to_string()));
    vars
}

/// `--show-env` (names only) and `--write-env` (mode 0600), both on stderr.
fn announce(ctx: &mut Ctx, plan: &Plan, session: &Session, vars: &[(String, String)]) -> Result<(), Fail> {
    if plan.show_env {
        for (name, _) in vars {
            ctx.out.note(&format!("env: {name}"));
        }
    }
    let Some(path) = &plan.write_env else { return Ok(()) };
    let text: String = vars.iter().map(|(k, v)| format!("{k}={v}\n")).collect();
    write_private(path, &text)?;
    let shown = path.display().to_string();
    ctx.out.note(&message("apps_dev_env_written", &[("path", &shown), ("expires_at", &session.expires_at)]));
    Ok(())
}

fn write_private(path: &Path, text: &str) -> Result<(), Fail> {
    let fail = |e: std::io::Error| Fail::failure(format!("{}: {e}", path.display()));
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    use std::io::Write;
    options.open(path).and_then(|mut f| f.write_all(text.as_bytes())).map_err(fail)
}

fn start_app(plan: &Plan, env: &std::collections::BTreeMap<String, String>) -> Result<Group, Fail> {
    let mut command = supervise::shell(&plan.start);
    command.current_dir(&plan.dir).env_clear().envs(env).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut app = Group::spawn(command).map_err(|e| Fail::failure(format!("{}: {e}", plan.start)))?;
    if let Some(out) = app.child.stdout.take() {
        supervise::relay(out, APP_PREFIX);
    }
    if let Some(err) = app.child.stderr.take() {
        supervise::relay(err, APP_PREFIX);
    }
    Ok(app)
}

/// The app's own exit: `ok` on status 0, else `app_exited`.
pub fn app_result(plan: &Plan, status: ExitStatus) -> Result<(), Fail> {
    if status.success() {
        return Ok(());
    }
    let shown = status.code().map_or_else(|| "a signal".to_string(), |c| c.to_string());
    Err(Fail::message(Exit::AppExited, "app_exited", &[("name", &plan.name), ("status", &shown)]))
}

/// Step 4's wait: the port accepts within 60 s. `false` when the app
/// already ended the session by exiting 0.
fn wait_app(plan: &Plan, run: &mut Run) -> Result<bool, Fail> {
    let stop = Arc::clone(&run.stop);
    let Some(app) = run.app.as_mut() else { return Ok(false) };
    let mut exited = None;
    let listening = supervise::wait_listening(plan.port, LISTEN_WAIT, &mut || {
        exited = exited.or_else(|| app.exited());
        stop.load(Ordering::SeqCst) || exited.is_some()
    });
    match (stop.load(Ordering::SeqCst), exited, listening) {
        (true, _, _) => Err(interrupted()),
        (_, Some(status), _) => app_result(plan, status).map(|()| false),
        (_, None, true) => Ok(true),
        (_, None, false) => {
            let port = plan.port.to_string();
            Err(Fail::message(Exit::AppNotListening, "app_not_listening", &[("name", &plan.name), ("port", &port)]))
        }
    }
}
