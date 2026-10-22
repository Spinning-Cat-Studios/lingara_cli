//! The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant
//! I1). One function, called by every dangerous command, so no command can
//! carry a weaker copy.
//!
//! - `-y --confirm <email>` replaces both prompts, interactive or not, and
//!   sends no read for a prompt; the email is checked before anything is
//!   destroyed. Neither flag alone is confirmation.
//! - Otherwise a non-interactive run is refused **before any request**, the
//!   read and any token exchange included.
//! - Interactive: the command's read fills the prompt, `y`/`yes` continues,
//!   then the typed email must match. There is no retry loop.

use crate::app::Ctx;
use crate::assets::message;
use crate::cli::ConfirmArgs;
use crate::exit::{Exit, Fail};

/// What the y/n question names: the object, its cascade, its pins.
pub struct Prompt {
    pub kind: &'static str,
    pub name: String,
    pub id: String,
    /// The kind's `consequence_*` line, or `consequence_version`.
    pub consequence: String,
    pub pinned_by: u64,
}

/// Trimmed and ASCII-lowercased: the comparison's rule and the form the
/// destructive request carries.
pub fn canonical(email: &str) -> String {
    email.trim().to_ascii_lowercase()
}

/// `dev@example.com` is `d…@example.com`; a one-character local part is
/// `…@example.com`. A typo stays catchable without printing the address.
pub fn hint(email: &str) -> String {
    let (local, domain) = email.split_once('@').unwrap_or((email, ""));
    let first: String = if local.chars().count() > 1 { local.chars().take(1).collect() } else { String::new() };
    format!("{first}\u{2026}@{domain}")
}

fn not_confirmed(id: &str) -> Fail {
    Fail::new(Exit::NotConfirmed, id, message(id, &[]))
}

/// The ritual; the canonical email the destructive request then carries.
pub fn confirm_destructive(
    ctx: &mut Ctx,
    flags: &ConfirmArgs,
    read: &mut dyn FnMut(&mut Ctx) -> Result<Prompt, Fail>,
) -> Result<String, Fail> {
    if let (true, Some(typed)) = (flags.yes, flags.confirm.as_deref()) {
        let email = canonical(&ctx.email()?);
        return if canonical(typed) == email { Ok(email) } else { Err(not_confirmed("confirm_email_mismatch")) };
    }
    if !ctx.app.terminal.interactive() {
        return Err(not_confirmed("confirm_non_tty_refused"));
    }
    let prompt = read(ctx)?;
    ctx.out.prompt(&format!("{} [y/N] ", question(&prompt)));
    let answer = ctx.app.terminal.read_line().unwrap_or_default();
    if !matches!(answer.trim().to_ascii_lowercase().as_str(), "y" | "yes") {
        return Err(not_confirmed("confirm_declined"));
    }
    let email = canonical(&ctx.email()?);
    ctx.out.prompt(&format!("{} ", message("confirm_type_email", &[("hint", &hint(&email))])));
    let typed = ctx.app.terminal.read_line().unwrap_or_default();
    if canonical(&typed) == email { Ok(email) } else { Err(not_confirmed("confirm_email_mismatch")) }
}

/// `confirm_yes_no`, the consequence, and `confirm_pinned_by` when pinned.
fn question(p: &Prompt) -> String {
    let mut lines = vec![message("confirm_yes_no", &[("kind", p.kind), ("name", &p.name), ("id", &p.id)]), p.consequence.clone()];
    if p.pinned_by > 0 {
        lines.push(message("confirm_pinned_by", &[("count", &p.pinned_by.to_string())]));
    }
    lines.join("\n")
}

#[cfg(test)]
#[path = "confirm_tests.rs"]
mod confirm_tests;
