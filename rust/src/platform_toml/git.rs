//! D4's two git questions: is a file tracked (a warning), and does git
//! ignore a path that would hold secrets (a refusal). Outside a work tree, or
//! with no `git` on `PATH`, there is no index to commit a file into, so both
//! answers are the permissive ones and the checks are skipped.

use std::path::Path;
use std::process::{Command, Stdio};

/// `git -C <dir> <args>`, quietly; `None` when git could not run.
fn git(dir: &Path, args: &[&str]) -> Option<bool> {
    let status = Command::new("git").arg("-C").arg(dir).args(args).stdout(Stdio::null()).stderr(Stdio::null()).status().ok()?;
    Some(status.success())
}

fn dir_of(path: &Path) -> &Path {
    path.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."))
}

/// Whether `dir` is inside a git work tree, with git available.
pub fn in_work_tree(dir: &Path) -> bool {
    git(dir, &["rev-parse", "--is-inside-work-tree"]) == Some(true)
}

/// Whether git tracks `path`; false outside a work tree.
pub fn tracked(path: &Path) -> bool {
    let dir = dir_of(path);
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    in_work_tree(dir) && git(dir, &["ls-files", "--error-unmatch", "--", &name]) == Some(true)
}

/// Whether git ignores `path`; true outside a work tree, where nothing can
/// be committed.
pub fn ignored(path: &Path) -> bool {
    let dir = dir_of(path);
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    !in_work_tree(dir) || git(dir, &["check-ignore", "-q", "--", &name]) == Some(true)
}
