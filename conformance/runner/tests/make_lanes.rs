//! The make half of coverage (ADR 16.10.26ac D8): every language in LANGS
//! sets CONFORMANCE_CMD_<lang>, checked before the Rust half runs.

use std::path::{Path, PathBuf};
use std::process::{Command, Output};

fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn make(target: &str, vars: &[String]) -> (bool, String) {
    let Output { status, stdout, stderr } = Command::new("make")
        .arg("-s")
        .arg("-C")
        .arg(repo())
        .arg(target)
        .args(vars)
        .env_remove("MAKEFLAGS")
        .env_remove("MFLAGS")
        .output()
        .expect("make runs");
    (status.success(), format!("{}{}", String::from_utf8_lossy(&stdout), String::from_utf8_lossy(&stderr)))
}

/// 16.10.26ac AC6
#[test]
fn every_language_in_langs_has_a_command() {
    // The committed cases cover the whole schema since ADR 16.10.26ad, so
    // `--strict` holds against the real one.
    let runner = format!("CONFORMANCE_RUNNER={}", env!("CARGO_BIN_EXE_cli-conformance"));
    let args = format!(
        "CONFORMANCE_ARGS=--cases {} --schema {} --routes {}",
        repo().join("conformance/cases").display(),
        repo().join("spec/cli.schema.json").display(),
        repo().join("conformance/routes.toml").display()
    );

    // `nokit` stands for a language whose make file is missing: every real
    // kit now sets its command (ADR 16.10.26af lists the last five).
    let (ok, out) = make("check-conformance-langs", &["LANGS=rust nokit".into(), "CONFORMANCE_CMD_rust=/bin/true".into()]);
    assert!(!ok && out.contains("no CONFORMANCE_CMD_<lang> for: nokit") && !out.contains("rust,"), "{out}");
    let both = ["LANGS=rust nokit".to_string(), "CONFORMANCE_CMD_rust=/bin/true".into(), "CONFORMANCE_CMD_nokit=/bin/true".into()];
    let (ok, out) = make("check-conformance-langs", &both);
    assert!(ok, "{out}");

    let (ok, out) = make("check-conformance-coverage", &["LANGS=nokit".into(), runner.clone(), args.clone()]);
    assert!(!ok && out.contains("for: nokit") && !out.contains("coverage holds"), "the Rust half never ran: {out}");
    let mut vars = both.to_vec();
    vars.extend([runner, args]);
    let (ok, out) = make("check-conformance-coverage", &vars);
    assert!(ok && out.contains("--strict on"), "a kit turns the per-command rules on: {out}");
}
