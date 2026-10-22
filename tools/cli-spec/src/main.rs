//! `cli-spec`: the one tool behind `spec/cli.schema.json` (ADR 16.10.26ab).
//!
//! - `check`     validates the schema and `messages.en.json` (D2);
//! - `docs`      renders the reference pages (D5);
//! - `tree-diff` compares a kit's `lingara __tree` with the schema (D4).
//!
//! Exit: 0 holds / 1 refusals or differences / 2 unreadable input.

mod check;
mod docs;
mod model;
mod tree;
mod walk;

#[cfg(test)]
mod check_tests;
#[cfg(test)]
mod docs_tests;
#[cfg(test)]
mod tree_tests;

use std::path::{Path, PathBuf};
use std::process::{Command as Process, ExitCode};

use clap::{Parser, Subcommand};

use model::{Messages, Spec};

#[derive(Parser)]
#[command(name = "cli-spec", about = "Check, document and diff the lingara command tree")]
struct Cli {
    #[command(subcommand)]
    verb: Verb,
}

#[derive(Subcommand)]
enum Verb {
    /// Validate the schema and the messages file beside it.
    Check {
        #[arg(long)]
        spec: PathBuf,
        /// Defaults to messages.en.json beside the schema.
        #[arg(long)]
        messages: Option<PathBuf>,
    },
    /// Render the reference pages and the schema copy under --out.
    Docs {
        #[arg(long)]
        spec: PathBuf,
        #[arg(long)]
        out: PathBuf,
    },
    /// Run `<kit command line> __tree` and compare it with the schema.
    TreeDiff {
        #[arg(long)]
        spec: PathBuf,
        #[arg(last = true, required = true)]
        kit: Vec<String>,
    },
}

fn main() -> ExitCode {
    let result = match Cli::parse().verb {
        Verb::Check { spec, messages } => run_check(&spec, messages),
        Verb::Docs { spec, out } => run_docs(&spec, &out),
        Verb::TreeDiff { spec, kit } => run_tree_diff(&spec, &kit),
    };
    match result {
        Ok(findings) if findings.is_empty() => ExitCode::SUCCESS,
        Ok(findings) => {
            for f in &findings {
                eprintln!("✗ {f}");
            }
            ExitCode::from(1)
        }
        Err(e) => {
            eprintln!("cli-spec: {e}");
            ExitCode::from(2)
        }
    }
}

fn read(path: &Path) -> Result<String, String> {
    std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))
}

fn load_spec(path: &Path) -> Result<(Spec, String), String> {
    let text = read(path)?;
    let spec = model::parse(&text).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok((spec, text))
}

fn run_check(spec_path: &Path, messages: Option<PathBuf>) -> Result<Vec<String>, String> {
    let messages_path = messages.unwrap_or_else(|| spec_path.with_file_name("messages.en.json"));
    let (spec, _) = load_spec(spec_path)?;
    let text = read(&messages_path)?;
    let msgs: Messages = model::parse(&text).map_err(|e| format!("{}: {e}", messages_path.display()))?;
    let findings = check::check(&spec, &msgs);
    if findings.is_empty() {
        println!("✓ {} holds", spec_path.display());
    }
    Ok(findings)
}

fn run_docs(spec_path: &Path, out: &Path) -> Result<Vec<String>, String> {
    let (spec, text) = load_spec(spec_path)?;
    let pages = docs::render(&spec, &text);
    docs::write(out, &pages)?;
    println!("✓ {} files under {}", pages.len(), out.display());
    Ok(Vec::new())
}

fn run_tree_diff(spec_path: &Path, kit: &[String]) -> Result<Vec<String>, String> {
    let (spec, _) = load_spec(spec_path)?;
    let output = Process::new(&kit[0])
        .args(&kit[1..])
        .arg("__tree")
        .output()
        .map_err(|e| format!("{}: {e}", kit[0]))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("`{} __tree` exited {}: {stderr}", kit.join(" "), output.status));
    }
    let kit_tree = tree::project_kit(&String::from_utf8_lossy(&output.stdout))?;
    let findings = tree::diff(&tree::project_spec(&spec), &kit_tree);
    if findings.is_empty() {
        println!("✓ `{} __tree` matches {}", kit.join(" "), spec_path.display());
    }
    Ok(findings)
}
