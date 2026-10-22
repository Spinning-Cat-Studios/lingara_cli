//! `cli-conformance`: drives a `lingara` binary through the conformance
//! cases against a mock platform (ADR 16.10.26ac).
//!
//! - `run`            spawns the kit once per case and judges it (D5, D6);
//! - `check-coverage` holds the suite to D7's rules.
//!
//! Exit: 0 holds / 1 a case failed or a coverage gap / 2 unreadable input.

mod case;
mod coverage;
mod invariants;
mod judge;
mod mock;
mod routes;
mod run;
mod spawn;

#[cfg(test)]
mod apps_keys_tests;
#[cfg(test)]
mod case_tests;
#[cfg(test)]
mod coverage_tests;
#[cfg(test)]
mod invariants_tests;
#[cfg(test)]
mod judge_tests;
#[cfg(test)]
mod spawn_tests;

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use clap::{Parser, Subcommand};

use spawn::Limits;

#[derive(Parser)]
#[command(name = "cli-conformance", about = "Run and check the lingara conformance suite")]
struct Cli {
    #[command(subcommand)]
    verb: Verb,
}

#[derive(Subcommand)]
enum Verb {
    /// Run every case against `<CONFORMANCE_CMD> <argv>`.
    Run {
        #[arg(long)]
        lang: String,
        #[command(flatten)]
        inputs: Inputs,
        /// A toolchain variable the kit's launcher needs (JAVA_HOME, GEM_HOME).
        #[arg(long = "pass-env", value_name = "NAME")]
        pass_env: Vec<String>,
        /// The runner's own tests shorten INTERACT_TIMEOUT; a case cannot.
        #[arg(long, hide = true)]
        interact_timeout_ms: Option<u64>,
        /// The runner's own tests shorten EXIT_TIMEOUT; a case cannot.
        #[arg(long, hide = true)]
        exit_timeout_ms: Option<u64>,
        #[arg(last = true, required = true)]
        command: Vec<String>,
    },
    /// Hold the suite to D7's coverage rules.
    CheckCoverage {
        #[command(flatten)]
        inputs: Inputs,
        /// Enforce the per-command rules too: make passes it once a kit exists.
        #[arg(long)]
        strict: bool,
    },
}

#[derive(clap::Args)]
struct Inputs {
    #[arg(long)]
    cases: PathBuf,
    #[arg(long)]
    schema: PathBuf,
    #[arg(long)]
    routes: PathBuf,
}

fn main() -> ExitCode {
    let result = match Cli::parse().verb {
        Verb::Run { lang, inputs, pass_env, interact_timeout_ms, exit_timeout_ms, command } => {
            let mut limits = Limits::default();
            limits.interact = interact_timeout_ms.map_or(limits.interact, Duration::from_millis);
            limits.exit = exit_timeout_ms.map_or(limits.exit, Duration::from_millis);
            let Inputs { cases, schema, routes } = inputs;
            run::run(run::RunArgs { lang, cases, schema, routes, pass_env, command, limits })
        }
        Verb::CheckCoverage { inputs, strict } => check_coverage(&inputs, strict),
    };
    match result {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::from(1),
        Err(e) => {
            eprintln!("cli-conformance: {e}");
            ExitCode::from(2)
        }
    }
}

fn check_coverage(inputs: &Inputs, strict: bool) -> Result<bool, String> {
    let schema = case::schema::load(&inputs.schema)?;
    let routes = routes::load(&inputs.routes)?;
    let contract_path = inputs.cases.parent().unwrap_or(Path::new(".")).join("CONTRACT.md");
    let contract = std::fs::read_to_string(contract_path).unwrap_or_default();
    let report = match case::load_dir(&inputs.cases) {
        Ok(cases) => coverage::check(&cases, &schema, &routes, &contract, strict),
        Err(errors) => coverage::Report { errors, ..coverage::Report::default() },
    };
    for w in &report.warnings {
        println!("warning: {w}");
    }
    for e in &report.errors {
        eprintln!("✗ {e}");
    }
    if report.errors.is_empty() {
        println!("✓ coverage holds ({} warning(s); --strict {})", report.warnings.len(), if strict { "on" } else { "off" });
    }
    Ok(report.errors.is_empty())
}
