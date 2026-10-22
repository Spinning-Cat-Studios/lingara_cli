//! Rendered text: examples name their command (D2.9), and no string the
//! docs render carries a secret or a private reference (D5), so a page the
//! site's leak scan would refuse cannot be generated.

use std::sync::LazyLock;

use regex::Regex;

use super::Refusals;
use crate::model::{Param, Spec};
use crate::walk::{CommandSite, Walk};

/// The site's secret pattern: a real-looking `lgr_` token.
static SECRET: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"lgr_[a-z]+_[A-Za-z0-9]{8,}").unwrap());

/// The rest of the site's leak scan. Spelled as fragments, so this shipped
/// file never names a private repository.
pub const LEAK_FRAGMENTS: [&str; 4] = ["_private", "_staging", "/Users/", "/home/"];

pub fn check(spec: &Spec, walk: &Walk, out: &mut Refusals) {
    for (i, row) in spec.exit_codes.iter().enumerate() {
        leaks(&format!("exit_codes[{i}].summary"), &row.summary, out);
    }
    params(&spec.global_flags, "global_flags", out);
    for (group, flags) in &spec.flag_groups {
        params(flags, &format!("flag_groups.{group}"), out);
    }
    for site in &walk.topics {
        if let Some(s) = &site.topic.summary {
            leaks(&format!("{}.summary", site.json), s, out);
        }
    }
    for site in &walk.commands {
        command(spec, site, out);
    }
}

fn command(spec: &Spec, site: &CommandSite, out: &mut Refusals) {
    let c = site.command;
    if let Some(s) = &c.summary {
        leaks(&format!("{}.summary", site.json), s, out);
    }
    params(&c.args, &format!("{}.args", site.json), out);
    params(&c.flags, &format!("{}.flags", site.json), out);
    for (i, ex) in c.examples.iter().enumerate() {
        let at = format!("{}.examples[{i}]", site.json);
        leaks(&format!("{at}.summary"), &ex.summary, out);
        leaks(&format!("{at}.argv"), &ex.argv, out);
        if SECRET.is_match(&ex.argv) {
            out.push(&format!("{at}.argv"), "carries a real-looking secret; use a placeholder like lgr_cs_<secret>");
        }
        if !names_command(&spec.binary, site, &ex.argv) {
            out.push(&format!("{at}.argv"), format!("does not begin `{} {}`", spec.binary, site.path()));
        }
    }
}

fn params(params: &[Param], at: &str, out: &mut Refusals) {
    for (i, p) in params.iter().enumerate() {
        if let Some(s) = &p.summary {
            leaks(&format!("{at}[{i}].summary"), s, out);
        }
        for v in p.values.iter().flatten() {
            leaks(&format!("{at}[{i}].values"), v, out);
        }
        if let Some(d) = &p.default {
            leaks(&format!("{at}[{i}].default"), &d.to_string(), out);
        }
    }
}

fn leaks(at: &str, text: &str, out: &mut Refusals) {
    for fragment in LEAK_FRAGMENTS.iter().filter(|f| text.contains(*f)) {
        out.push(at, format!("contains `{fragment}`, which the site's leak scan refuses"));
    }
}

/// `argv` is the binary, then one spelling of the command's path, then
/// nothing or a space.
fn names_command(binary: &str, site: &CommandSite, argv: &str) -> bool {
    let words: Vec<&str> = argv.split_whitespace().collect();
    if words.first() != Some(&binary) {
        return false;
    }
    site.spellings.iter().any(|spelling| {
        spelling.len() < words.len() && spelling.iter().zip(&words[1..]).all(|(a, b)| a == b)
    })
}
