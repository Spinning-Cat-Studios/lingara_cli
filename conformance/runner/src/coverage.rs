//! `check-coverage` (ADR 16.10.26ac D7 rules 1–6).
//!
//! Rule 2 and rule 3's per-command parts (the trio and the `conflicts`
//! cases) are enforced only under `--strict`, which make passes exactly when
//! a kit exists; without it they warn. Every other rule always fails.

use crate::case::schema::Schema;
use crate::case::{BEHAVIOURS, Behaviour, Case, Confirm, Loaded, check};
use crate::routes::Routes;

#[derive(Debug, Default)]
pub struct Report {
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
}

impl Report {
    fn gap(&mut self, strict: bool, what: String) {
        if strict { self.errors.push(what) } else { self.warnings.push(what) }
    }
}

/// `contract` is `CONTRACT.md`'s text; a behaviour counts for rule 6 once
/// its `## B<n>` heading exists and is not marked `(reserved)`.
pub fn check(cases: &[Loaded], schema: &Schema, routes: &Routes, contract: &str, strict: bool) -> Report {
    let mut report = Report::default();
    for loaded in cases {
        report.errors.extend(check::check(loaded, schema, routes));
    }
    let cases: Vec<&Case> = cases.iter().map(|l| &l.case).collect();
    per_command(&cases, schema, strict, &mut report);
    suite_rituals(&cases, &mut report);
    platform_disabled(&cases, &mut report);
    refresh(&cases, &mut report);
    behaviours(&cases, contract, &mut report);
    report
}

fn per_command(cases: &[&Case], schema: &Schema, strict: bool, report: &mut Report) {
    for command in &schema.commands {
        let mine: Vec<&&Case> = cases.iter().filter(|c| c.command.as_deref() == Some(command.path.as_str())).collect();
        if mine.is_empty() {
            report.gap(strict, format!("rule 2: `{}` has no case", command.path));
        }
        if command.dangerous {
            for ritual in [Confirm::NonTtyRefused, Confirm::NonTtyFlags, Confirm::TtyOk] {
                if !mine.iter().any(|c| c.confirm == Some(ritual)) {
                    report.gap(strict, format!("rule 3: dangerous `{}` has no {ritual:?} case", command.path));
                }
            }
        }
        for set in &command.conflicts {
            if !mine.iter().any(|c| conflict_case(c, set)) {
                report.gap(strict, format!("rule 3: `{}` conflicts {set:?} has no usage case", command.path));
            }
        }
    }
}

/// Passes two of the set's flags, expects `usage` and allows no request.
fn conflict_case(case: &Case, set: &[String]) -> bool {
    let passes = |flag: &String| case.argv.iter().any(|a| a == &format!("--{flag}") || a.starts_with(&format!("--{flag}=")));
    let no_requests = case.exchanges.as_ref().is_none_or(|x| x.items.is_empty());
    case.expect.exit == "usage" && no_requests && set.iter().filter(|f| passes(f)).count() >= 2
}

fn suite_rituals(cases: &[&Case], report: &mut Report) {
    for ritual in [Confirm::TtyDeclined, Confirm::TtyMismatch, Confirm::NonTtyFlagsMismatch] {
        if !cases.iter().any(|c| c.confirm == Some(ritual)) {
            report.errors.push(format!("rule 3: the suite has no {ritual:?} case"));
        }
    }
}

fn cites(case: &Case, b: Behaviour, exit: &str) -> bool {
    case.behaviours.contains(&b) && case.expect.exit == exit
}

fn platform_disabled(cases: &[&Case], report: &mut Report) {
    let login = |c: &&&Case| c.command.as_deref() == Some("login");
    let rules: [(&str, Box<dyn Fn(&&&Case) -> bool>); 4] = [
        ("a login expecting platform_disabled", Box::new(|c| cites(c, Behaviour::B4, "platform_disabled") && login(c))),
        ("a developer route expecting platform_disabled", Box::new(|c| cites(c, Behaviour::B4, "platform_disabled") && !login(c))),
        ("a coded 404 expecting not_found", Box::new(|c| cites(c, Behaviour::B4, "not_found"))),
        ("a token refusal expecting auth_required", Box::new(|c| cites(c, Behaviour::B4, "auth_required"))),
    ];
    for (what, rule) in rules {
        if !cases.iter().any(|c| rule(&c)) {
            report.errors.push(format!("rule 4: B4 has no case on {what}"));
        }
    }
}

/// How many responses in a case answer `status`.
fn statuses(case: &Case, status: u16) -> usize {
    case.exchanges.iter().flat_map(|x| &x.items).filter(|i| i.response.status() == status).count()
}

fn refresh(cases: &[&Case], report: &mut Report) {
    let empty_404 = |c: &Case| {
        c.exchanges.iter().flat_map(|x| &x.items).any(|i| i.response.status() == 404 && i.response.is_empty())
    };
    let rules: [(&str, Box<dyn Fn(&Case) -> bool>); 3] = [
        ("a 401-refresh case expecting ok", Box::new(|c| cites(c, Behaviour::B2, "ok") && statuses(c, 401) >= 1)),
        ("a second-401 case expecting auth_required", Box::new(|c| cites(c, Behaviour::B2, "auth_required") && statuses(c, 401) >= 2)),
        ("an empty-404 case expecting failure", Box::new(|c| cites(c, Behaviour::B2, "failure") && empty_404(c))),
    ];
    for (what, rule) in rules {
        if !cases.iter().any(|c| rule(c)) {
            report.errors.push(format!("rule 5: B2 has no {what}"));
        }
    }
}

/// The behaviours `CONTRACT.md` writes out: a `## B<n>` heading not marked
/// `(reserved)`.
pub fn written(contract: &str) -> Vec<Behaviour> {
    let heading = |b: Behaviour| {
        let prefix = format!("## {b:?} ");
        contract.lines().any(|l| l.starts_with(&prefix) && !l.contains("(reserved)"))
    };
    BEHAVIOURS.into_iter().filter(|b| heading(*b)).collect()
}

fn behaviours(cases: &[&Case], contract: &str, report: &mut Report) {
    for b in written(contract) {
        if !cases.iter().any(|c| c.behaviours.contains(&b)) {
            report.errors.push(format!("rule 6: no case cites {b:?}"));
        }
    }
}
