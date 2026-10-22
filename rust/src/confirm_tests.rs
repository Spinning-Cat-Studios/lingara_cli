use std::cell::Cell;

use super::*;
use crate::fakes::{Script, app, pipe, tty, with_ctx};

fn prompt() -> Prompt {
    Prompt { kind: "OAuth client", name: "My server".into(), id: "oc1".into(), consequence: "Its tokens stop.".into(), pinned_by: 2 }
}

fn flags(yes: bool, confirm: Option<&str>) -> ConfirmArgs {
    ConfirmArgs { yes, confirm: confirm.map(str::to_string) }
}

/// Runs the ritual; its result, how often the read ran, the requests sent
/// and stderr.
fn ritual(terminal: crate::fakes::FakeTerminal, f: ConfirmArgs) -> (Result<String, Fail>, usize, usize, String) {
    let script = Script::default();
    let mut app = app(&script, terminal);
    let reads = Cell::new(0);
    let (result, _, err) = with_ctx(&mut app, false, |ctx| {
        confirm_destructive(ctx, &f, &mut |_| {
            reads.set(reads.get() + 1);
            Ok(prompt())
        })
    });
    (result, reads.get(), script.log.borrow().len(), err)
}

fn exit_code(result: &Result<String, Fail>) -> Option<&str> {
    result.as_ref().err().map(|f| f.code.as_str())
}

/// 16.10.26ad AC3 the ritual is y/n then the email
#[test]
fn the_ritual_is_yn_then_the_email() {
    // y, then the email in another case with spaces: proceeds, canonical.
    let (ok, reads, sent, err) = ritual(tty(&["y", "  Dev@Conformance.INVALID "]), flags(false, None));
    assert_eq!((ok.as_deref(), reads, sent), (Ok("dev@conformance.invalid"), 1, 0));
    assert!(err.contains("Delete OAuth client My server (oc1)?\nIts tokens stop.\nObjects pinning it: 2. [y/N] "), "{err}");
    assert!(err.contains("Type your developer email (d\u{2026}@conformance.invalid) to confirm: "), "{err}");
    assert_eq!(ritual(tty(&["YES", "dev@conformance.invalid"]), flags(false, None)).0.as_deref(), Ok("dev@conformance.invalid"));

    // n, or nothing at all: declined, exit 6, the email never asked.
    for answer in ["n", "", "yep"] {
        let (declined, _, _, err) = ritual(tty(&[answer]), flags(false, None));
        assert_eq!(exit_code(&declined), Some("confirm_declined"));
        assert_eq!(declined.unwrap_err().exit, Exit::NotConfirmed);
        assert!(!err.contains("Type your developer email"));
    }
    // A mismatch: exit 6, no retry.
    let (mismatch, _, _, _) = ritual(tty(&["y", "someone@else.invalid", "dev@conformance.invalid"]), flags(false, None));
    assert_eq!(exit_code(&mismatch), Some("confirm_email_mismatch"));

    // -y and a matching --confirm: no prompt and no read, in a pipe or a terminal.
    for terminal in [pipe(), tty(&[])] {
        let (flagged, reads, sent, err) = ritual(terminal, flags(true, Some("DEV@conformance.invalid")));
        assert_eq!((flagged.as_deref(), reads, sent, err.as_str()), (Ok("dev@conformance.invalid"), 0, 0, ""));
    }
    let (wrong, reads, _, _) = ritual(pipe(), flags(true, Some("someone@else.invalid")));
    assert_eq!((exit_code(&wrong), reads), (Some("confirm_email_mismatch"), 0));

    // One flag alone, in a pipe: refused before any call.
    for one in [flags(true, None), flags(false, Some("dev@conformance.invalid")), flags(false, None)] {
        let (refused, reads, sent, _) = ritual(pipe(), one);
        assert_eq!((exit_code(&refused), reads, sent), (Some("confirm_non_tty_refused"), 0, 0));
    }
}

#[test]
fn the_hint_shows_one_letter_and_the_domain() {
    assert_eq!(hint("dev@example.com"), "d\u{2026}@example.com");
    assert_eq!(hint("d@example.com"), "\u{2026}@example.com");
    assert_eq!(canonical("  A@B.c\n"), "a@b.c");
}
