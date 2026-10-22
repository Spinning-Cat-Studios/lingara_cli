//! `lingara webhook inbound|outbound …` (D1, D2).

mod inbound;
mod outbound;
mod replace;

use crate::app::Ctx;
use crate::cli::webhook::WebhookCmd;
use crate::exit::Fail;

pub fn run(ctx: &mut Ctx, cmd: WebhookCmd) -> Result<(), Fail> {
    match cmd {
        WebhookCmd::Inbound(cmd) => inbound::run(ctx, cmd),
        WebhookCmd::Outbound(cmd) => outbound::run(ctx, cmd),
    }
}
