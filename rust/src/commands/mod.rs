//! One module per topic, one function per verb (D9). Each topic matches its
//! own subcommands, so no dispatch table grows past one screen.

pub mod apps;
pub mod canvas;
pub mod creates;
pub mod deletes;
pub mod embed;
pub mod oauth_client;
pub mod objects;
pub mod session;
pub mod webhook;

use serde_json::Value;

use crate::app::Ctx;
use crate::cli::Top;
use crate::exit::Fail;
use crate::output::table::cell;
use objects::Kind;

pub fn dispatch(ctx: &mut Ctx, command: Top) -> Result<(), Fail> {
    match command {
        Top::Login(args) => session::login(ctx, args.device),
        Top::Logout => session::logout(ctx),
        Top::Whoami => session::whoami(ctx),
        Top::Apps(cmd) => apps::run(ctx, cmd),
        Top::Embed(cmd) => embed::run(ctx, cmd),
        Top::OauthClient(cmd) => oauth_client::run(ctx, cmd),
        Top::Webhook(cmd) => webhook::run(ctx, cmd),
        Top::Canvas(cmd) => canvas::run(ctx, cmd),
        Top::Tree => {
            ctx.out.json_value(&crate::tree::tree());
            Ok(())
        }
    }
}

/// A list's human columns: each a heading and the member it reads.
pub type Columns = &'static [(&'static str, &'static str)];

/// Every page of `k`, printed: one JSON array, or the schema's columns.
pub fn print_list(ctx: &mut Ctx, k: &Kind, columns: Columns) -> Result<(), Fail> {
    let items = objects::list(ctx, k)?;
    print_rows(ctx, &items, columns);
    Ok(())
}

pub fn print_rows(ctx: &mut Ctx, items: &[Value], columns: Columns) {
    let headings: Vec<&str> = columns.iter().map(|(h, _)| *h).collect();
    let cells = |item: &Value| columns.iter().map(|(_, key)| cell(&item[*key])).collect();
    ctx.out.list(items, &headings, &cells);
}

#[cfg(test)]
#[path = "commands_tests.rs"]
mod commands_tests;
