//! Running one kit invocation (D5 steps 4–5).
//!
//! `tty: false` is three pipes. `tty: true` makes **stdin and stderr one
//! pseudo-terminal**, the child's controlling terminal, while stdout stays a
//! pipe: prompts are interactive and data stays separately judgeable.

use std::collections::BTreeMap;
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::Path;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use super::{Limits, browser};
use crate::judge::find_in_order;

const POLL: Duration = Duration::from_millis(10);
/// How long the streams may drain after the child has exited.
const DRAIN: Duration = Duration::from_secs(1);

/// One `interact` step, resolved: the fragments to see in order, then what
/// to type; or the browser.
#[derive(Debug, Clone)]
pub enum Step {
    Wait { label: String, fragments: Vec<String>, send: Option<String> },
    Browser,
}

/// Everything one run needs.
pub struct Job<'a> {
    pub command: &'a [String],
    pub argv: &'a [String],
    pub env: &'a BTreeMap<String, String>,
    pub cwd: &'a Path,
    pub tty: bool,
    pub stdin: Option<&'a str>,
    pub steps: &'a [Step],
    pub limits: Limits,
    /// ADR 16.10.26ae D11's `signal`: sent once stderr shows the message.
    pub signal: Option<SignalPlan>,
}

/// A signal to send once stderr holds `fragments` in order `count` times.
#[derive(Debug, Clone)]
pub struct SignalPlan {
    pub fragments: Vec<String>,
    pub count: usize,
    pub signal: i32,
}

/// How many times `fragments` appear in order, one after another.
pub fn occurrences(text: &str, fragments: &[String]) -> usize {
    let (mut n, mut at) = (0, 0);
    while let Some(end) = find_in_order(text, fragments, at) {
        n += 1;
        at = end;
    }
    n
}

#[derive(Debug, Default)]
pub struct Outcome {
    /// `None` when the child was killed by a signal or never ran.
    pub exit: Option<i32>,
    pub stdout: Vec<u8>,
    /// The stderr pipe, or the terminal's transcript with `\r\n` as `\n`.
    pub stderr: String,
    /// Timeouts, spawn and browser failures.
    pub failures: Vec<String>,
    /// When the last `send` was written, and how many were, for I1 (b).
    pub last_send: Option<Instant>,
    pub sends: usize,
}

#[derive(Clone)]
struct Reader {
    buf: Arc<Mutex<Vec<u8>>>,
    done: Arc<AtomicBool>,
}

impl Reader {
    fn start(mut src: impl Read + Send + 'static) -> Reader {
        let reader = Reader { buf: Arc::default(), done: Arc::default() };
        let r = reader.clone();
        std::thread::spawn(move || {
            let mut chunk = [0u8; 4096];
            while let Ok(n) = src.read(&mut chunk) {
                if n == 0 {
                    break;
                }
                r.buf.lock().unwrap_or_else(|p| p.into_inner()).extend_from_slice(&chunk[..n]);
            }
            r.done.store(true, Ordering::SeqCst);
        });
        reader
    }

    fn text(&self) -> String {
        let bytes = self.buf.lock().unwrap_or_else(|p| p.into_inner()).clone();
        String::from_utf8_lossy(&bytes).replace("\r\n", "\n")
    }

    fn bytes(&self) -> Vec<u8> {
        self.buf.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }
}

struct Running {
    child: Child,
    stdout: Reader,
    stderr: Reader,
    terminal: Option<File>,
}

pub fn run(job: &Job) -> Outcome {
    let started = Instant::now();
    let mut running = match if job.tty { spawn_tty(job) } else { spawn_pipes(job) } {
        Ok(r) => r,
        Err(e) => return Outcome { failures: vec![format!("spawn `{}`: {e}", job.command.join(" "))], ..Outcome::default() },
    };
    let mut out = Outcome::default();
    let interacted = interact(job, &mut running, &mut out);
    let deadline = started + job.limits.exit;
    let pid = running.child.id() as i32;
    let mut pending = job.signal.clone();
    let stderr = running.stderr.clone();
    let mut tick = move || {
        let due = pending.as_ref().is_some_and(|p| p.count > 0 && occurrences(&stderr.text(), &p.fragments) >= p.count);
        if let Some(plan) = pending.take_if(|_| due) {
            // SAFETY: kill(2) on the child this run spawned; no memory is touched.
            unsafe { nix::libc::kill(pid, plan.signal) };
        }
    };
    let status = if interacted { wait_until(&mut running.child, deadline, &mut tick) } else { None };
    if status.is_none() {
        let _ = running.child.kill();
        let _ = running.child.wait();
        if interacted {
            out.failures.push(format!("timeout: still running after EXIT_TIMEOUT ({} s)", job.limits.exit.as_secs()));
        }
    }
    drop(running.terminal.take());
    let drain = Instant::now() + DRAIN;
    while !(running.stdout.done.load(Ordering::SeqCst) && running.stderr.done.load(Ordering::SeqCst)) && Instant::now() < drain {
        std::thread::sleep(POLL);
    }
    out.exit = status.and_then(|s| s.code().or_else(|| s.signal().map(|_| -1)).filter(|c| *c >= 0));
    out.stdout = running.stdout.bytes();
    out.stderr = running.stderr.text();
    out
}

fn base_command(job: &Job) -> Command {
    let mut command = Command::new(&job.command[0]);
    command.args(&job.command[1..]).args(job.argv).env_clear().envs(job.env).current_dir(job.cwd);
    command.stdout(Stdio::piped());
    command
}

fn spawn_pipes(job: &Job) -> io::Result<Running> {
    let mut command = base_command(job);
    command.stdin(Stdio::piped()).stderr(Stdio::piped());
    let mut child = command.spawn()?;
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(job.stdin.unwrap_or("").as_bytes());
    }
    let stdout = Reader::start(child.stdout.take().ok_or_else(|| io::Error::other("no stdout pipe"))?);
    let stderr = Reader::start(child.stderr.take().ok_or_else(|| io::Error::other("no stderr pipe"))?);
    Ok(Running { child, stdout, stderr, terminal: None })
}

fn spawn_tty(job: &Job) -> io::Result<Running> {
    let pty = nix::pty::openpty(None, None).map_err(io::Error::from)?;
    let mut command = base_command(job);
    command.stdin(Stdio::from(pty.slave.try_clone()?)).stderr(Stdio::from(pty.slave));
    // SAFETY: setsid and ioctl are async-signal-safe; together they make the
    // terminal (already dup'ed onto fd 0) the new session's controlling one.
    unsafe {
        command.pre_exec(|| {
            if nix::libc::setsid() < 0 {
                return Err(io::Error::last_os_error());
            }
            nix::libc::ioctl(0, nix::libc::TIOCSCTTY as _, 0);
            Ok(())
        });
    }
    let mut child = command.spawn()?;
    // `command` drops here, and with it the parent's copies of the slave.
    drop(command);
    let master = File::from(pty.master);
    let stderr = Reader::start(master.try_clone()?);
    let stdout = Reader::start(child.stdout.take().ok_or_else(|| io::Error::other("no stdout pipe"))?);
    Ok(Running { child, stdout, stderr, terminal: Some(master) })
}

/// Runs every step; `false` when one timed out (the child is then killed).
fn interact(job: &Job, running: &mut Running, out: &mut Outcome) -> bool {
    let mut cursor = 0;
    for step in job.steps {
        let ok = match step {
            Step::Wait { label, fragments, send } => {
                wait_for(job, running, out, (label, fragments), &mut cursor) && write_send(running, out, send.as_deref())
            }
            Step::Browser => browse(job, running, out),
        };
        if !ok {
            return false;
        }
    }
    true
}

fn wait_for(job: &Job, running: &Running, out: &mut Outcome, (label, fragments): (&str, &[String]), cursor: &mut usize) -> bool {
    let deadline = Instant::now() + job.limits.interact;
    loop {
        let text = running.stderr.text();
        if let Some(end) = find_in_order(&text, fragments, *cursor) {
            *cursor = end;
            return true;
        }
        if Instant::now() >= deadline {
            let secs = job.limits.interact.as_secs_f32();
            out.failures.push(format!("timeout: interact.expect {label} not seen within INTERACT_TIMEOUT ({secs} s)"));
            return false;
        }
        std::thread::sleep(POLL);
    }
}

fn write_send(running: &mut Running, out: &mut Outcome, send: Option<&str>) -> bool {
    let (Some(text), Some(terminal)) = (send, running.terminal.as_mut()) else { return true };
    if let Err(e) = terminal.write_all(text.as_bytes()).and_then(|()| terminal.flush()) {
        out.failures.push(format!("interact.send: {e}"));
        return false;
    }
    out.last_send = Some(Instant::now());
    out.sends += 1;
    true
}

fn browse(job: &Job, running: &Running, out: &mut Outcome) -> bool {
    let deadline = Instant::now() + job.limits.interact;
    let url = loop {
        if let Some(url) = browser::first_url(&running.stderr.text()) {
            break url;
        }
        if Instant::now() >= deadline {
            out.failures.push("timeout: browser: no URL on stderr within INTERACT_TIMEOUT".into());
            return false;
        }
        std::thread::sleep(POLL);
    };
    if let Err(e) = browser::follow(&url) {
        out.failures.push(e);
    }
    true
}

fn wait_until(child: &mut Child, deadline: Instant, tick: &mut dyn FnMut()) -> Option<ExitStatus> {
    loop {
        if let Ok(Some(status)) = child.try_wait() {
            return Some(status);
        }
        if Instant::now() >= deadline {
            return None;
        }
        tick();
        std::thread::sleep(POLL);
    }
}
