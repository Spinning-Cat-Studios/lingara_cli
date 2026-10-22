//! `cli-spec docs` (ADR 16.10.26ab D5): the reference pages, rendered from
//! the schema alone, in schema order, with no timestamps, so two runs are
//! byte-identical and `make check-docs` can compare bytes.

mod section;

use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use crate::model::{ExitCode, Spec};
use crate::walk::{walk, CommandSite};

/// Relative path under `--out` → file content.
pub type Pages = BTreeMap<String, String>;

/// The schema copy Public reads beside the pages.
pub const SCHEMA_COPY: &str = "cli.schema.json";

pub fn render(spec: &Spec, schema_text: &str) -> Pages {
    let w = walk(spec);
    let mut pages = Pages::new();
    let leaves: Vec<&CommandSite> = w.commands.iter().filter(|c| c.words.len() == 1).collect();
    let names: Vec<&str> = leaves.iter().map(|c| c.command.name.as_str()).collect();
    let description = format!("The commands outside any topic: {}.", names.join(", "));
    pages.insert("reference/auth.md".into(), page(spec, ("auth", &description, 1), "", &leaves));
    for (i, topic) in spec.topics.iter().enumerate() {
        let leaves: Vec<&CommandSite> =
            w.commands.iter().filter(|c| c.words.len() > 1 && c.words[0] == topic.name).collect();
        let fallback = format!("The `{} {}` commands.", spec.binary, topic.name);
        let description = topic.summary.clone().unwrap_or(fallback);
        let intro = aliases_line(&topic.aliases);
        pages.insert(
            format!("reference/{}.md", topic.name),
            page(spec, (&topic.name, &description, i + 2), &intro, &leaves),
        );
    }
    let last = spec.topics.len() + 2;
    pages.insert("reference/exit-codes.md".into(), exit_page(spec, last));
    pages.insert(SCHEMA_COPY.into(), schema_text.to_string());
    pages
}

fn aliases_line(aliases: &[String]) -> String {
    if aliases.is_empty() {
        return String::new();
    }
    let list: Vec<String> = aliases.iter().map(|a| format!("`{a}`")).collect();
    format!("Alias: {}.\n\n", list.join(", "))
}

fn front_matter(title: &str, description: &str, order: usize) -> String {
    format!("---\ntitle: {}\ndescription: {}\norder: {order}\n---\n\n", quoted(title), quoted(description))
}

/// A YAML double-quoted scalar.
fn quoted(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

fn page(spec: &Spec, head: (&str, &str, usize), intro: &str, leaves: &[&CommandSite]) -> String {
    let (title, description, order) = head;
    let mut out = front_matter(title, description, order);
    out.push_str(&format!("{description}\n\n{intro}"));
    out.push_str(&globals_line(spec));
    for site in leaves {
        out.push_str(&section::render(spec, site));
    }
    out
}

fn globals_line(spec: &Spec) -> String {
    if spec.global_flags.is_empty() {
        return String::new();
    }
    let flags: Vec<String> = spec.global_flags.iter().map(|f| format!("`{}`", section::flag_spelling(f))).collect();
    format!("Every command also takes the global flags {}.\n\n", flags.join(", "))
}

fn exit_page(spec: &Spec, order: usize) -> String {
    let description = format!("The exit codes every `{}` variant returns.", spec.binary);
    let mut out = front_matter("exit codes", &description, order);
    out.push_str(&format!("{description}\n\n| Code | Name | Meaning |\n|---|---|---|\n"));
    let mut rows: Vec<&ExitCode> = spec.exit_codes.iter().collect();
    rows.sort_by_key(|r| r.code);
    for r in rows {
        out.push_str(&format!("| {} | `{}` | {} |\n", r.code, r.name, section::cell(&r.summary)));
    }
    out
}

/// Writes `pages` under `out`, removing any stale reference page so the
/// directory holds exactly D5's file set.
pub fn write(out: &Path, pages: &Pages) -> Result<(), String> {
    let reference = out.join("reference");
    if let Ok(entries) = fs::read_dir(&reference) {
        for entry in entries.flatten() {
            let rel = format!("reference/{}", entry.file_name().to_string_lossy());
            if !pages.contains_key(&rel) {
                fs::remove_file(entry.path()).map_err(|e| format!("{}: {e}", entry.path().display()))?;
            }
        }
    }
    for (rel, content) in pages {
        let path = out.join(rel);
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        }
        fs::write(&path, content).map_err(|e| format!("{}: {e}", path.display()))?;
    }
    Ok(())
}
