//! One leaf command's section: a `###` heading whose text is its full path
//! without the binary (Public's tests match that text), then usage, args,
//! flags, aliases, conflicts, the danger notice, columns, exit codes and
//! examples (ADR 16.10.26ab D5).

use crate::check::IMPLICIT_EXITS;
use crate::model::{Command, Param, ParamType, Spec};
use crate::walk::CommandSite;

pub fn render(spec: &Spec, site: &CommandSite) -> String {
    let c = site.command;
    let mut out = format!("### {}\n\n", site.path());
    if let Some(s) = &c.summary {
        out.push_str(&format!("{s}\n\n"));
    }
    out.push_str(&format!("```sh\n{}\n```\n\n", usage(spec, site)));
    out.push_str(&notes(spec, site));
    out.push_str(&args_table(c));
    out.push_str(&flags_table(spec, c));
    out.push_str(&exit_table(spec, c));
    for ex in &c.examples {
        out.push_str(&format!("{}:\n\n```sh\n{}\n```\n\n", ex.summary, ex.argv));
    }
    out
}

fn usage(spec: &Spec, site: &CommandSite) -> String {
    let mut words = vec![spec.binary.clone(), site.path()];
    words.extend(positional(site.command));
    for f in site.command.flags.iter().filter(|f| f.required) {
        words.push(flag_spelling(f));
    }
    words.push("[flags]".into());
    words.join(" ")
}

fn positional(c: &Command) -> Vec<String> {
    let word = |a: &Param| {
        let dots = if a.repeatable { "..." } else { "" };
        if a.required { format!("<{}>{dots}", a.name) } else { format!("[{}]{dots}", a.name) }
    };
    c.args.iter().map(word).collect()
}

/// `--name <value>`, or `--json` for a bool.
pub fn flag_spelling(f: &Param) -> String {
    match f.kind {
        ParamType::Bool => format!("--{}", f.name),
        _ => format!("--{} <{}>", f.name, f.value_name.as_deref().unwrap_or(&f.name)),
    }
}

fn notes(spec: &Spec, site: &CommandSite) -> String {
    let mut out = String::new();
    if site.spellings.len() > 1 {
        let also: Vec<String> =
            site.spellings[1..].iter().map(|s| format!("`{} {}`", spec.binary, s.join(" "))).collect();
        out.push_str(&format!("Also spelled {}.\n\n", also.join(", ")));
    }
    if site.command.dangerous {
        out.push_str(&format!(
            "> **Dangerous.** This command deletes or replaces something, so it asks twice: \
             a y/n question, then your developer email typed back. Where nobody can answer, \
             as in CI, pass both flags instead: `{} -y --confirm <email>`.\n\n",
            [spec.binary.clone(), site.path()].into_iter().chain(positional(site.command)).collect::<Vec<_>>().join(" ")
        ));
    }
    for set in &site.command.conflicts {
        let flags: Vec<String> = set.iter().map(|n| format!("`--{n}`")).collect();
        out.push_str(&format!("At most one of {} may be given.\n\n", flags.join(", ")));
    }
    if let Some(output) = &site.command.output {
        let cols: Vec<String> = output.columns.iter().map(|c| format!("`{c}`")).collect();
        out.push_str(&format!("Table columns: {}.\n\n", cols.join(", ")));
    }
    out
}

fn args_table(c: &Command) -> String {
    if c.args.is_empty() {
        return String::new();
    }
    let mut out = String::from("| Argument | Type | Required | Description |\n|---|---|---|---|\n");
    for a in &c.args {
        let summary = a.summary.as_deref().unwrap_or("");
        out.push_str(&format!("| `{}` | {} | {} | {} |\n", a.name, type_cell(a), yes_no(a.required), cell(summary)));
    }
    out.push('\n');
    out
}

fn flags_table(spec: &Spec, c: &Command) -> String {
    let groups = c.flag_groups.iter().filter_map(|g| spec.flag_groups.get(g)).flatten();
    let flags: Vec<&Param> = c.flags.iter().chain(groups).collect();
    if flags.is_empty() {
        return String::new();
    }
    let mut out = String::from("| Flag | Type | Required | Default | Env | Description |\n|---|---|---|---|---|---|\n");
    for f in flags {
        let short = f.short.as_deref().map(|s| format!("-{s}, ")).unwrap_or_default();
        let default = f.default.as_ref().map(default_cell).unwrap_or_default();
        let env = f.env.as_deref().map(|e| format!("`{e}`")).unwrap_or_default();
        let summary = cell(f.summary.as_deref().unwrap_or(""));
        let name = format!("`{short}{}`", flag_spelling(f));
        out.push_str(&format!("| {name} | {} | {} | {default} | {env} | {summary} |\n", type_cell(f), yes_no(f.required)));
    }
    out.push('\n');
    out
}

fn exit_table(spec: &Spec, c: &Command) -> String {
    let mut rows: Vec<_> = spec
        .exit_codes
        .iter()
        .filter(|r| IMPLICIT_EXITS.contains(&r.name.as_str()) || c.exit_codes.contains(&r.name))
        .collect();
    rows.sort_by_key(|r| r.code);
    let mut out = String::from("| Exit | Name |\n|---|---|\n");
    for r in rows {
        out.push_str(&format!("| {} | `{}` |\n", r.code, r.name));
    }
    out.push('\n');
    out
}

fn type_cell(p: &Param) -> String {
    let mut t = p.kind.as_str().to_string();
    if let Some(values) = &p.values {
        t = format!("{t}: {}", values.iter().map(|v| format!("`{v}`")).collect::<Vec<_>>().join(", "));
    }
    if p.repeatable {
        t.push_str(", repeatable");
    }
    t
}

fn default_cell(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => format!("`{s}`"),
        other => format!("`{other}`"),
    }
}

fn yes_no(b: bool) -> &'static str {
    if b { "yes" } else { "no" }
}

/// A table cell: a pipe would end the cell, and a newline the row.
pub fn cell(s: &str) -> String {
    s.replace('|', "\\|").replace('\n', " ")
}
