//! The children's process groups and their stop (ADR 16.10.26ae AC24).

use std::collections::BTreeMap;
#[cfg(unix)]
use std::io::{BufRead, BufReader};
use std::process::Stdio;
use std::time::{Duration, Instant};

use super::{Group, child_env, shell};

/// Whether a process is still there (unix: signal 0).
#[cfg(unix)]
fn alive(pid: i32) -> bool {
    // SAFETY: signal 0 only checks that the process exists.
    unsafe { libc::kill(pid, 0) == 0 }
}

/// 16.10.26ae AC24: `start` runs through the platform shell in its own
/// process group, so a signal to the CLI's group reaches neither child; the
/// stop signals the whole group, a grandchild included, and returns once the
/// leader has exited.
#[cfg(unix)]
#[test]
fn children_get_their_own_group_and_stop_in_order() {
    let mut command = shell("sleep 30 & echo $!; wait");
    command.stdout(Stdio::piped()).stderr(Stdio::null());
    let mut group = Group::spawn(command).expect("sh runs");
    let leader = group.pid() as i32;
    let mut line = String::new();
    BufReader::new(group.child.stdout.take().unwrap()).read_line(&mut line).unwrap();
    let grandchild: i32 = line.trim().parse().expect("the grandchild's pid");
    // SAFETY: getpgid(2) reads two process group ids.
    let (theirs, ours) = unsafe { (libc::getpgid(leader), libc::getpgid(0)) };
    assert_eq!(theirs, leader, "the child leads its own group");
    assert_ne!(theirs, ours, "a Ctrl+C to the CLI's group never reaches it");
    let started = Instant::now();
    group.stop(Duration::from_secs(5));
    assert!(started.elapsed() < Duration::from_secs(5), "SIGTERM was enough");
    std::thread::sleep(Duration::from_millis(100));
    assert!(!alive(grandchild), "the whole group was stopped");
}

/// 16.10.26ae AC24 on Windows, where conformance does not run: the child
/// starts in a new process group and the stop ends its whole tree.
#[cfg(windows)]
#[test]
fn children_get_their_own_group_and_stop_in_order() {
    let mut command = shell("ping -n 30 127.0.0.1 > NUL");
    command.stdout(Stdio::null()).stderr(Stdio::null());
    let mut group = Group::spawn(command).expect("cmd runs");
    let started = Instant::now();
    group.stop(Duration::from_secs(5));
    assert!(group.exited().is_some(), "the tree was stopped");
    assert!(started.elapsed() < Duration::from_secs(20));
}

/// 22.10.26d AC4: a nested quoted `cmd /c "…"` reaches cmd.exe intact, so
/// its grandchild starts and writes its file; the stop still ends the tree.
#[cfg(windows)]
#[test]
fn a_quoted_grandchild_starts() {
    let dir = std::env::temp_dir().join(format!("lingara-supervise-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let pid_file = dir.join("pid");
    let _ = std::fs::remove_file(&pid_file);
    let script = format!(
        "start /b cmd /c \"echo grandchild>{} & ping -n 60 127.0.0.1 >NUL\" & ping -n 60 127.0.0.1 >NUL",
        pid_file.display()
    );
    let mut command = shell(&script);
    command.stdout(Stdio::null()).stderr(Stdio::null());
    let mut group = Group::spawn(command).expect("cmd runs");
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut started = false;
    while Instant::now() < deadline {
        if std::fs::read_to_string(&pid_file).is_ok_and(|s| !s.trim().is_empty()) {
            started = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    group.stop(Duration::from_secs(2));
    let _ = std::fs::remove_dir_all(&dir);
    assert!(started, "the grandchild never started");
    assert!(group.exited().is_some(), "the tree was stopped");
}

#[test]
fn the_child_never_inherits_the_cli_credentials() {
    let parent: BTreeMap<String, String> = [
        ("PATH", "/bin"),
        ("LINGARA_PLATFORM_CLIENT_ID", "lgr_cid_x"),
        ("LINGARA_PLATFORM_CLIENT_SECRET", "lgr_cs_x"),
        ("LINGARA_APP_SECRET", "stale"),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    let env = child_env(&parent, &[("LINGARA_APP_SECRET".into(), "fresh".into())]);
    assert!(!env.keys().any(|k| k.starts_with("LINGARA_PLATFORM_")));
    assert_eq!(env["LINGARA_APP_SECRET"], "fresh");
    assert_eq!(env["PATH"], "/bin");
}
