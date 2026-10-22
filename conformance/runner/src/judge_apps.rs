//! ADR 16.10.26ae D11's three judges, over what an `apps` case left behind:
//! the `platform_dev` call log (`expect.calls`), the fake app's record of
//! its environment (`expect.child_env`) and the files under the case's
//! directory (`expect.files`).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::case::apps::{CallMatch, ChildEnv, Files};
use crate::judge::drop_nulls;
use crate::mock::fill_json;
use crate::mock::platform_dev::{DevCall, Minted};

fn matches(entry: &CallMatch, call: &DevCall, subs: &[(String, String)]) -> bool {
    let body = |j: &Value| drop_nulls(&fill_json(j, subs));
    entry.route == call.route && entry.json.as_ref().is_none_or(|want| call.json.as_ref().map(drop_nulls) == Some(body(want)))
}

/// An ordered subsequence: each entry matches in order, calls between them
/// are allowed, and `count` bounds one entry's run of consecutive matches.
/// `count: {min: 0, max: 0}` says the call never comes after the entries
/// before it.
pub fn calls(want: &[CallMatch], log: &[DevCall], subs: &[(String, String)]) -> Vec<String> {
    let mut at = 0;
    for (i, entry) in want.iter().enumerate() {
        let found = (at..log.len()).find(|&j| matches(entry, &log[j], subs));
        if found.is_none() && entry.count.is_some_and(|c| c.min == 0) {
            continue;
        }
        let Some(first) = found else {
            let seen: Vec<&str> = log.iter().map(|c| c.route.as_str()).collect();
            return vec![format!("calls[{i}]: no {} {} after call {at}; the log is {seen:?}", entry.route, entry.json.as_ref().map_or(String::new(), Value::to_string))];
        };
        let run = match entry.count {
            Some(_) => (first..log.len()).take_while(|&j| matches(entry, &log[j], subs)).count(),
            None => 1,
        };
        if let Some(c) = entry.count
            && (run < c.min || c.max.is_some_and(|m| run > m))
        {
            return vec![format!("calls[{i}]: {} matched {run} time(s) in a row, outside {}..{:?}", entry.route, c.min, c.max)];
        }
        at = first + run;
    }
    Vec::new()
}

fn sha256(text: &str) -> String {
    Sha256::digest(text.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

/// The fake app's record: `{names: [...], hashes: {NAME: sha256}}`.
pub fn child_env(want: &ChildEnv, record: &Path, minted: &[Minted]) -> Vec<String> {
    let Some(seen) = std::fs::read_to_string(record).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else {
        return vec![format!("child_env: the fake app wrote no record at {}", record.display())];
    };
    let names: Vec<&str> = seen["names"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
    let mut out: Vec<String> = want.present.iter().filter(|n| !names.contains(&n.as_str())).map(|n| format!("child_env: {n} is not set")).collect();
    for prefix in &want.absent_prefix {
        out.extend(names.iter().filter(|n| n.starts_with(prefix.as_str())).map(|n| format!("child_env: {n} reached the app")));
    }
    let last = minted.last();
    let session: BTreeMap<&str, Option<&String>> =
        [("LINGARA_APP_SECRET", last.map(|m| &m.app_secret)), ("LINGARA_CLIENT_SECRET", last.and_then(|m| m.client_secret.as_ref()))].into();
    for name in &want.equals_session {
        let expected = session.get(name.as_str()).copied().flatten().map(|s| sha256(s));
        if expected.is_none() || seen["hashes"][name].as_str() != expected.as_deref() {
            out.push(format!("child_env: {name} is not the session's minted value"));
        }
    }
    out
}

fn files_under(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = entry.path();
        if path.is_dir() { out.extend(files_under(&path)) } else { out.push(path) }
    }
    out.sort();
    out
}

fn relative(files: &[PathBuf], root: &Path) -> Vec<PathBuf> {
    files.iter().filter_map(|f| f.strip_prefix(root).ok().map(Path::to_path_buf)).collect()
}

/// A golden path as the scaffold writes it: a segment starting `_` is a
/// dotfile, stored renamed so a golden `.gitignore` never hides the golden
/// `platform.toml` from git (spec/templates.md's own rule).
fn written(golden: &Path) -> PathBuf {
    let segment = |c: std::path::Component| {
        let s = c.as_os_str().to_string_lossy();
        s.strip_prefix('_').map_or_else(|| s.to_string(), |rest| format!(".{rest}"))
    };
    golden.components().map(segment).collect()
}

/// Each top-level entry of the golden directory against the same path
/// under the case's directory, byte for byte, both ways.
fn golden(dir: &Path, home: &Path) -> Vec<String> {
    let mut out = Vec::new();
    for top in std::fs::read_dir(dir).into_iter().flatten().flatten().map(|e| e.file_name()) {
        let (want_root, got_root) = (dir.join(&top), home.join(written(Path::new(&top))));
        let stored = if want_root.is_dir() { relative(&files_under(&want_root), dir) } else { vec![PathBuf::from(&top)] };
        let want: Vec<PathBuf> = stored.iter().map(|p| written(p)).collect();
        let got = if got_root.is_dir() { relative(&files_under(&got_root), home) } else if got_root.exists() { vec![written(Path::new(&top))] } else { Vec::new() };
        out.extend(got.iter().filter(|p| !want.contains(p)).map(|p| format!("files: {} is not in the golden tree", p.display())));
        for (stored, path) in stored.iter().zip(&want) {
            match (std::fs::read(dir.join(stored)), std::fs::read(home.join(path))) {
                (Ok(a), Ok(b)) if a == b => {}
                (Ok(_), Ok(_)) => out.push(format!("files: {} differs from the golden tree", path.display())),
                _ => out.push(format!("files: {} is missing", path.display())),
            }
        }
    }
    out
}

/// `expect.files`, and every minted secret absent from every file.
pub fn files(want: Option<&Files>, home: &Path, goldens: &Path, minted: &[Minted]) -> Vec<String> {
    let mut out = Vec::new();
    if let Some(dir) = want.and_then(|w| w.golden.as_ref()) {
        out.extend(golden(&goldens.join(dir), home));
    }
    for path in want.map(|w| w.present.as_slice()).unwrap_or_default() {
        if !home.join(path).exists() {
            out.push(format!("files: {path} is missing"));
        }
    }
    let mut absent: Vec<&str> = want.map(|w| w.absent_text.iter().map(String::as_str).collect()).unwrap_or_default();
    absent.extend(minted.iter().flat_map(|m| std::iter::once(m.app_secret.as_str()).chain(m.client_secret.as_deref())));
    for file in files_under(home) {
        let text = String::from_utf8_lossy(&std::fs::read(&file).unwrap_or_default()).into_owned();
        out.extend(absent.iter().filter(|s| text.contains(**s)).map(|s| format!("files: {} holds {s:?}", file.display())));
    }
    out
}
