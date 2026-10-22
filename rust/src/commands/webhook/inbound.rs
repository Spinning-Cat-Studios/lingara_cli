//! Inbound webhooks (D2): a `webhook_in` object (body `{event_types[]}`) on
//! a confidential client, the credential a sender uses for `POST
//! /v1/events`. `events:write` comes from the child's projection, so the
//! client's own body never names it, and deleting the webhook clears it.

use serde_json::{Value, json};

use crate::app::Ctx;
use crate::assets::message;
use crate::cli::webhook::{InboundCmd, InboundCreate, InboundUpdate};
use crate::commands::creates::Creation;
use crate::commands::deletes::{delete_object, parent};
use crate::commands::objects::{self, OAUTH_CLIENT, WEBHOOK_INBOUND};
use crate::commands::{Columns, print_list};
use crate::exit::Fail;

const COLUMNS: Columns = &[("ID", "id"), ("NAME", "name"), ("CLIENT", "parent_id"), ("EVENTS", "event_types")];

pub fn run(ctx: &mut Ctx, cmd: InboundCmd) -> Result<(), Fail> {
    match cmd {
        InboundCmd::Create(args) => create(ctx, &args),
        InboundCmd::List => print_list(ctx, &WEBHOOK_INBOUND, COLUMNS),
        InboundCmd::Get(arg) => objects::print_get(ctx, &WEBHOOK_INBOUND, &arg.id),
        InboundCmd::Update(args) => update(ctx, &args),
        InboundCmd::Delete(args) => {
            delete_object(ctx, &WEBHOOK_INBOUND, &args, |o| message("consequence_webhook_inbound", &[("client", &parent(o))]))
        }
    }
}

/// No `--event` omits `event_types`, which 16.10.26v §5 projects as every
/// inbound type.
fn body(events: &[String]) -> Value {
    if events.is_empty() { json!({}) } else { json!({ "event_types": events }) }
}

fn create(ctx: &mut Ctx, args: &InboundCreate) -> Result<(), Fail> {
    let client_body = json!({ "client_type": "confidential", "allowed_scopes": [] });
    let mut made = Creation::default();
    let result = made.object(ctx, &OAUTH_CLIENT, &args.name, None, client_body).and_then(|client| {
        let client_id = client["id"].as_str().unwrap_or_default().to_string();
        made.secret(ctx, &OAUTH_CLIENT, &client_id)?;
        let hook = made.object(ctx, &WEBHOOK_INBOUND, &args.name, Some(&client_id), body(&args.event))?;
        Ok((WEBHOOK_INBOUND, hook["id"].as_str().unwrap_or_default().to_string()))
    });
    let hook = made.conclude(ctx, result)?;
    let ingest = format!("{}/v1/events", ctx.app.http.bases.api.trim_end_matches('/'));
    let client_id = hook["client_id"].as_str().unwrap_or("-").to_string();
    ctx.out.note(&format!("Senders POST to {ingest} as client {client_id}, with its secret and an Idempotency-Key header."));
    Ok(())
}

fn update(ctx: &mut Ctx, args: &InboundUpdate) -> Result<(), Fail> {
    let mut last: Option<Value> = None;
    if let Some(name) = &args.name {
        last = Some(objects::rename(ctx, &WEBHOOK_INBOUND, &args.id, name)?);
    }
    if !args.event.is_empty() {
        last = Some(objects::body_update(ctx, &WEBHOOK_INBOUND, &args.id, &|b| b["event_types"] = json!(args.event))?);
    }
    let value = last.ok_or_else(|| Fail::usage("nothing to update: pass --name or --event"))?;
    ctx.out.value(&value);
    Ok(())
}
