//! B3, the confirmation ritual, on `client delete`.

use std::io::{BufRead, IsTerminal, Write};

use serde_json::json;

use crate::net::Session;
use crate::{Ctx, Fail, exit};

const NON_TTY_REFUSED: &str = "Refusing to delete without confirmation: this is not an interactive terminal. \
Re-run with -y --confirm <your developer email> to confirm.";
const DECLINED: &str = "Not confirmed. Nothing was deleted.";
const MISMATCH: &str = "That is not your developer email. Nothing was deleted.";

fn canonical(email: &str) -> String {
    email.trim().to_ascii_lowercase()
}

fn not_confirmed(code: &str, text: &str) -> Fail {
    Fail::new(exit::NOT_CONFIRMED, code, text)
}

fn ask(prompt: &str) -> String {
    eprint!("{prompt} ");
    let _ = std::io::stderr().flush();
    let mut line = String::new();
    let _ = std::io::stdin().lock().read_line(&mut line);
    line
}

pub fn delete(ctx: &Ctx, id: &str) -> Result<(), Fail> {
    let interactive =
        (std::io::stdin().is_terminal() && std::io::stderr().is_terminal()) || ctx.script.has("prompt_in_pipe");
    let flagged = ctx.flag("yes").is_some().then(|| ctx.flag("confirm").and_then(|(_, v)| v.clone())).flatten();
    if flagged.is_some() && !interactive && ctx.script.has("refuse_pipe_with_flags") {
        return Err(not_confirmed("confirm_non_tty_refused", NON_TTY_REFUSED));
    }
    match (flagged, interactive) {
        (Some(typed), _) => {
            let mut session = Session::open(&ctx.script)?;
            if canonical(&typed) != canonical(&session.email()) {
                return Err(not_confirmed("confirm_email_mismatch", MISMATCH));
            }
            send_delete(ctx, &mut session, id)
        }
        (None, true) => prompted(ctx, id),
        (None, false) => Err(not_confirmed("confirm_non_tty_refused", NON_TTY_REFUSED)),
    }
}

/// B3.2: read the object, ask y/n, then the email, then delete.
fn prompted(ctx: &Ctx, id: &str) -> Result<(), Fail> {
    let mut session = Session::open(&ctx.script)?;
    let object = session.call("GET", &format!("/platform/objects/{id}"), None, "oauth_client.get")?;
    let name = object["name"].as_str().unwrap_or("");
    let answer = ask(&format!("Delete OAuth client {name} ({id})?\n{}\n[y/N]", consequence(&object)));
    if !matches!(answer.trim().to_ascii_lowercase().as_str(), "y" | "yes") {
        return Err(not_confirmed("confirm_declined", DECLINED));
    }
    let typed = ask("Type your developer email (d***@conformance.invalid) to confirm:");
    if canonical(&typed) != canonical(&session.email()) {
        return Err(not_confirmed("confirm_email_mismatch", MISMATCH));
    }
    send_delete(ctx, &mut session, id)
}

/// ADR 16.10.26ad D5 step 3: the cascade, counted from the read's `children`.
fn consequence(object: &serde_json::Value) -> String {
    let children = object["children"].as_array().cloned().unwrap_or_default();
    let count = |kinds: &[&str]| children.iter().filter(|c| kinds.contains(&c["kind"].as_str().unwrap_or(""))).count();
    let (apps, embeds, webhooks) = (count(&["app"]), count(&["embed"]), count(&["webhook_in", "webhook_out"]));
    format!("Deleted with it: apps {apps}, embeds {embeds}, webhooks {webhooks}. Its tokens stop working at once.")
}

/// B3.6: the canonical email in the body of the DELETE itself.
fn send_delete(ctx: &Ctx, session: &mut Session, id: &str) -> Result<(), Fail> {
    let body = json!({ "confirm": canonical(&session.email()) });
    session.call("DELETE", &format!("/platform/objects/{id}"), Some(body), "oauth_client.delete")?;
    if ctx.json {
        println!("{{}}");
    }
    Ok(())
}
