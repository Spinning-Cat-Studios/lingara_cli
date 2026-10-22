//! Args, flags, flag groups, `dangerous` and `conflicts` (D2.2, D2.4, D2.6,
//! D2.10).

use std::collections::BTreeSet;

use super::{is_kebab, is_snake, Refusals};
use crate::model::{Param, ParamType, Spec};
use crate::walk::{CommandSite, Walk};

/// The group every dangerous command carries, and only those (D2.4).
pub const CONFIRM_GROUP: &str = "confirm";

pub fn check(spec: &Spec, walk: &Walk, out: &mut Refusals) {
    for (i, flag) in spec.global_flags.iter().enumerate() {
        let at = format!("global_flags[{i}]");
        flag_rules(&at, flag, out);
        if flag.name == "version" {
            out.push(&format!("{at}.name"), "a global `version` is the framework's");
        }
    }
    for (group, flags) in &spec.flag_groups {
        for (i, flag) in flags.iter().enumerate() {
            flag_rules(&format!("flag_groups.{group}[{i}]"), flag, out);
        }
    }
    confirm_group(spec, out);
    for site in &walk.commands {
        command(spec, site, out);
    }
}

fn flag_rules(at: &str, flag: &Param, out: &mut Refusals) {
    param_rules(at, flag, out);
    if !is_kebab(&flag.name) {
        out.push(&format!("{at}.name"), format!("`{}` is not kebab-case", flag.name));
    }
    if flag.name == "help" {
        out.push(&format!("{at}.name"), "`help` is the framework's");
    }
    match flag.short.as_deref() {
        Some("h") => out.push(&format!("{at}.short"), "`h` is the framework's"),
        Some(s) if s.chars().count() != 1 || !s.chars().all(|c| c.is_ascii_alphanumeric()) => {
            out.push(&format!("{at}.short"), format!("`{s}` is not one ASCII letter or digit"))
        }
        _ => {}
    }
}

fn arg_rules(at: &str, arg: &Param, out: &mut Refusals) {
    param_rules(at, arg, out);
    if !is_snake(&arg.name) {
        out.push(&format!("{at}.name"), format!("`{}` is not snake_case", arg.name));
    }
    if arg.short.is_some() {
        out.push(&format!("{at}.short"), "an arg has no short form");
    }
}

fn param_rules(at: &str, p: &Param, out: &mut Refusals) {
    let is_enum = p.kind == ParamType::Enum;
    match (&p.values, is_enum) {
        (Some(_), false) => out.push(&format!("{at}.values"), "`values` is for `enum` only"),
        (None, true) => out.push(&format!("{at}.values"), "an `enum` needs its `values`"),
        (Some(v), true) if v.is_empty() => out.push(&format!("{at}.values"), "is empty"),
        _ => {}
    }
}

/// Neither confirm flag may come from the environment or configuration
/// (roadmap 16.10.26r D6.3).
fn confirm_group(spec: &Spec, out: &mut Refusals) {
    let Some(flags) = spec.flag_groups.get(CONFIRM_GROUP) else {
        return;
    };
    for (i, flag) in flags.iter().enumerate() {
        let at = format!("flag_groups.{CONFIRM_GROUP}[{i}]");
        if flag.env.is_some() {
            out.push(&format!("{at}.env"), "confirmation never comes from the environment");
        }
        if flag.default.is_some() {
            out.push(&format!("{at}.default"), "confirmation never has a default");
        }
    }
}

fn command(spec: &Spec, site: &CommandSite, out: &mut Refusals) {
    let c = site.command;
    for (i, arg) in c.args.iter().enumerate() {
        arg_rules(&format!("{}.args[{i}]", site.json), arg, out);
    }
    for (i, flag) in c.flags.iter().enumerate() {
        flag_rules(&format!("{}.flags[{i}]", site.json), flag, out);
    }
    for (i, group) in c.flag_groups.iter().enumerate() {
        if !spec.flag_groups.contains_key(group) {
            out.push(&format!("{}.flag_groups[{i}]", site.json), format!("no flag group `{group}`"));
        }
    }
    let confirmed = c.flag_groups.iter().any(|g| g == CONFIRM_GROUP);
    if c.dangerous && !confirmed {
        out.push(&site.json, "a dangerous command must carry the `confirm` flag group");
    } else if confirmed && !c.dangerous {
        out.push(&site.json, "only a dangerous command carries the `confirm` flag group");
    }
    collisions(spec, site, out);
    conflicts(spec, site, out);
}

/// Every flag a command answers to: globals, then its groups, then its own.
pub fn effective_flags<'a>(spec: &'a Spec, site: &CommandSite<'a>) -> Vec<&'a Param> {
    let groups = site.command.flag_groups.iter().filter_map(|g| spec.flag_groups.get(g)).flatten();
    spec.global_flags.iter().chain(groups).chain(site.command.flags.iter()).collect()
}

fn collisions(spec: &Spec, site: &CommandSite, out: &mut Refusals) {
    let (mut long, mut short) = (BTreeSet::new(), BTreeSet::new());
    for flag in effective_flags(spec, site) {
        if !long.insert(flag.name.as_str()) {
            out.push(&site.json, format!("`--{}` is declared twice (globals and groups included)", flag.name));
        }
        if let Some(s) = flag.short.as_deref()
            && !short.insert(s)
        {
            out.push(&site.json, format!("`-{s}` is declared twice (globals and groups included)"));
        }
    }
}

fn conflicts(spec: &Spec, site: &CommandSite, out: &mut Refusals) {
    let names: BTreeSet<&str> = effective_flags(spec, site).iter().map(|f| f.name.as_str()).collect();
    for (i, set) in site.command.conflicts.iter().enumerate() {
        let at = format!("{}.conflicts[{i}]", site.json);
        if set.len() < 2 {
            out.push(&at, "a conflicts set names at least two flags");
        }
        for name in set.iter().filter(|n| !names.contains(n.as_str())) {
            out.push(&at, format!("`{name}` is not a flag of this command"));
        }
    }
}
