//! The children `apps dev` runs (ADR 16.10.26ae D5, D7 steps 4 and 8): the
//! app's environment, built from the parent's with the CLI's own platform
//! credentials scrubbed; the platform shell (`sh -c`, `cmd /C`); a process
//! group per child, so a terminal's Ctrl+C reaches only the CLI; and a stop
//! that signals the whole group, SIGTERM then SIGKILL after a grace period
//! (the process tree on Windows).

use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::process::{Child, Command, ExitStatus};
use std::time::{Duration, Instant};

/// The CLI's own credentials, never the app's (D5): `LINGARA_PLATFORM_*`.
pub const SCRUBBED_PREFIX: &str = "LINGARA_PLATFORM_";

/// `line` through the platform shell, exactly as the developer would type it.
#[cfg(windows)]
pub fn shell(line: &str) -> Command {
    // cmd.exe does not parse its command line the CommandLineToArgvW way,
    // so `args`' quoting (an embedded " as \") would mangle a nested
    // `cmd /c "…"`; raw_arg hands it the line verbatim (ADR 22.10.26d D3).
    use std::os::windows::process::CommandExt;
    let mut c = Command::new("cmd");
    c.arg("/C").raw_arg(line);
    c
}

/// `line` through the platform shell, exactly as the developer would type it.
#[cfg(not(windows))]
pub fn shell(line: &str) -> Command {
    let mut c = Command::new("sh");
    c.args(["-c", line]);
    c
}

/// The parent's environment, minus the CLI's credentials, plus `extra`
/// (later entries win).
pub fn child_env(parent: &BTreeMap<String, String>, extra: &[(String, String)]) -> BTreeMap<String, String> {
    let mut env: BTreeMap<String, String> = parent.iter().filter(|(k, _)| !k.starts_with(SCRUBBED_PREFIX)).map(|(k, v)| (k.clone(), v.clone())).collect();
    env.extend(extra.iter().cloned());
    env
}

/// Starts the child in a process group of its own.
pub fn own_group(command: &mut Command) {
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        command.creation_flags(CREATE_NEW_PROCESS_GROUP);
    }
}

/// A running child that leads its own process group.
pub struct Group {
    pub child: Child,
}

impl Group {
    pub fn spawn(mut command: Command) -> std::io::Result<Group> {
        own_group(&mut command);
        Ok(Group { child: command.spawn()? })
    }

    pub fn pid(&self) -> u32 {
        self.child.id()
    }

    /// The exit status, if it has exited.
    pub fn exited(&mut self) -> Option<ExitStatus> {
        self.child.try_wait().ok().flatten()
    }

    /// Signals the whole group: SIGTERM, then SIGKILL once `grace` passes.
    pub fn stop(&mut self, grace: Duration) {
        #[cfg(unix)]
        {
            let group = -(self.pid() as i32);
            // SAFETY: kill(2) on our own child's process group; no memory is touched.
            unsafe { libc::kill(group, libc::SIGTERM) };
            let deadline = Instant::now() + grace;
            while self.exited().is_none() && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(50));
            }
            // The group may outlive its leader: kill whatever is left of it.
            // SAFETY: as above.
            unsafe { libc::kill(group, libc::SIGKILL) };
        }
        #[cfg(windows)]
        {
            let _ = grace;
            let pid = self.pid().to_string();
            let _ = Command::new("taskkill").args(["/T", "/F", "/PID", &pid]).status();
        }
        let _ = self.child.wait();
    }
}

/// Copies `source` to stderr line by line, each line after `prefix`.
pub fn relay(source: impl Read + Send + 'static, prefix: &'static str) {
    std::thread::spawn(move || {
        for line in BufReader::new(source).lines().map_while(Result::ok) {
            let mut err = std::io::stderr().lock();
            let _ = writeln!(err, "{prefix}{line}");
        }
    });
}

/// Whether `127.0.0.1:port` accepts a connection before `timeout`, giving up
/// early when `stop` says so.
pub fn wait_listening(port: u16, timeout: Duration, stop: &mut dyn FnMut() -> bool) -> bool {
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if TcpStream::connect_timeout(&address, Duration::from_millis(250)).is_ok() {
            return true;
        }
        if stop() {
            return false;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

#[cfg(test)]
#[path = "supervise_tests.rs"]
mod supervise_tests;
