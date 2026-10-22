//! Holding an `apps dev` session (ADR 16.10.26ae D7 steps 5–7): bring the
//! tunnel up, point the dev version at it, then renew the lease every
//! `lease_secs / 3` until Ctrl+C, the app's exit or a lost session ends it.
//! A cloudflared that dies is respawned up to five times, 1 s, 2 s, 4 s, 8 s
//! and 16 s apart, and each new URL is pointed at; a sixth death, or a first
//! incarnation that never comes up, is `tunnel_failed`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use serde_json::json;

use super::dev::{Plan, Target};
use super::dev_api::{self, Session};
use super::dev_run::{Run, app_result, interrupted, lost};
use crate::app::Ctx;
use crate::assets::message;
use crate::exit::{Exit, Fail};
use crate::tunnel::cloudflared::Tunnel;

const BACKOFF_SECS: [u64; 5] = [1, 2, 4, 8, 16];
const TICK: Duration = Duration::from_millis(100);

fn tunnel_failed(plan: &Plan, reason: &str, tail: String) -> Fail {
    let fail = Fail::message(Exit::TunnelFailed, "tunnel_failed", &[("name", &plan.name), ("reason", reason)]);
    tail.lines().fold(fail, |f, line| f.hint(line.to_string()))
}

/// Step 6: `PATCH …/dev {render_url, session_id, draft_version}`, then the
/// ready line. A `dev_session_held` here means a take-over since the open.
fn point(ctx: &mut Ctx, plan: &Plan, session: &Session, url: &str, run: &mut Run) -> Result<(), Fail> {
    let body = |draft_version| json!({ "render_url": url, "session_id": session.id, "draft_version": draft_version });
    if let Err(fail) = dev_api::patch_with_retry(ctx, &plan.id, body) {
        if fail.exit == Exit::DevSessionHeld {
            run.lost = true;
            return Err(lost(&plan.name));
        }
        return Err(fail);
    }
    ctx.out.note(&message("apps_dev_ready", &[("name", &plan.name), ("url", url)]));
    run.urls.push(url.to_string());
    Ok(())
}

/// One incarnation, up: its URL, with the tunnel; else its last lines.
fn incarnation(plan: &Plan, binary: &std::path::Path, stop: &AtomicBool) -> Result<(Tunnel, String), String> {
    let mut tunnel = Tunnel::spawn(binary, plan.port).map_err(|f| f.message)?;
    let mut stopped = || stop.load(Ordering::SeqCst);
    let up = tunnel.hostname(&mut stopped).filter(|host| tunnel.ready(host, plan.probe.as_deref(), &mut stopped));
    match up {
        Some(host) => Ok((tunnel, format!("https://{host}{}", plan.render_path))),
        None => {
            let tail = tunnel.tail();
            tunnel.stop();
            Err(tail)
        }
    }
}

/// Steps 5 and 6 for the first incarnation, or the fixed `--render-url`.
pub fn first_url(ctx: &mut Ctx, plan: &Plan, session: &Session, run: &mut Run) -> Result<(), Fail> {
    let url = match &plan.target {
        Target::Fixed(url) => url.clone(),
        Target::Cloudflared(binary) => match incarnation(plan, binary, &run.stop) {
            Ok((tunnel, url)) => {
                run.tunnel = Some(tunnel);
                url
            }
            Err(_) if run.stopped() => return Err(interrupted()),
            Err(tail) => return Err(tunnel_failed(plan, "no URL, or no answer through it, within 30 s", tail)),
        },
    };
    point(ctx, plan, session, &url, run)
}

/// Sleeps `secs`, a tick at a time, unless the session is stopped.
fn backoff(secs: u64, run: &Run) -> Result<(), Fail> {
    let until = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < until {
        if run.stopped() {
            return Err(interrupted());
        }
        std::thread::sleep(TICK);
    }
    Ok(())
}

/// Step 7's respawn: until an incarnation is up and pointed at, or a sixth death.
fn respawn(ctx: &mut Ctx, plan: &Plan, session: &Session, run: &mut Run) -> Result<(), Fail> {
    let Target::Cloudflared(binary) = &plan.target else { return Ok(()) };
    let mut tail = String::new();
    if let Some(mut dead) = run.tunnel.take() {
        tail = dead.tail();
        dead.stop();
    }
    loop {
        let Some(&secs) = BACKOFF_SECS.get(run.respawns) else {
            return Err(tunnel_failed(plan, "cloudflared died a sixth time", tail));
        };
        backoff(secs, run)?;
        run.respawns += 1;
        match incarnation(plan, binary, &run.stop) {
            Ok((tunnel, url)) => {
                run.tunnel = Some(tunnel);
                return point(ctx, plan, session, &url, run);
            }
            Err(_) if run.stopped() => return Err(interrupted()),
            Err(last) => tail = last,
        }
    }
}

/// One scheduled renew. A refusal that means the session is over ends the
/// run with no `DELETE`; anything else waits for the next scheduled renew,
/// because a `POST` that was answered is never retried.
fn renew(ctx: &mut Ctx, plan: &Plan, session: &Session, run: &mut Run) -> Result<(), Fail> {
    match dev_api::renew(ctx, &plan.id, &session.id) {
        Err(fail) if fail.exit == Exit::DevSessionLost => {
            run.lost = true;
            Err(lost(&plan.name))
        }
        _ => Ok(()),
    }
}

/// Step 7: the loop, until the first thing that ends the session.
pub fn hold(ctx: &mut Ctx, plan: &Plan, session: &Session, run: &mut Run) -> Result<(), Fail> {
    let period = Duration::from_secs((session.lease_secs / 3).max(1));
    let mut next_renew = Instant::now() + period;
    loop {
        if run.stopped() {
            return Err(interrupted());
        }
        if let Some(status) = run.app.as_mut().and_then(|a| a.exited()) {
            return app_result(plan, status);
        }
        if run.tunnel.as_mut().is_some_and(|t| !t.alive()) {
            respawn(ctx, plan, session, run)?;
        }
        if Instant::now() >= next_renew {
            renew(ctx, plan, session, run)?;
            next_renew = Instant::now() + period;
        }
        std::thread::sleep(TICK);
    }
}
