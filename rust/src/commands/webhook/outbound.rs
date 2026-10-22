//! Outbound webhooks (D2): a `webhook_out` object (body `{url,
//! event_types[]}`) under its client. A body update keeps the endpoint and
//! its signing secret; enabled is object-level, never version body.

use serde_json::{Value, json};

use super::replace;
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::webhook::{OutboundCmd, OutboundCreate, OutboundUpdate};
use crate::commands::creates::Creation;
use crate::commands::deletes::delete_object;
use crate::commands::objects::{self, WEBHOOK_OUTBOUND};
use crate::commands::{Columns, print_list};
use crate::exit::Fail;
use crate::http::Request;
use crate::http::routes::{Op, object};

const COLUMNS: Columns = &[("ID", "id"), ("CLIENT", "parent_id"), ("URL", "url"), ("EVENTS", "event_types"), ("ENABLED", "enabled")];

pub fn run(ctx: &mut Ctx, cmd: OutboundCmd) -> Result<(), Fail> {
    match cmd {
        OutboundCmd::Create(args) => create(ctx, &args).map(|_| ()),
        OutboundCmd::List => print_list(ctx, &WEBHOOK_OUTBOUND, COLUMNS),
        OutboundCmd::Get(arg) => objects::print_get(ctx, &WEBHOOK_OUTBOUND, &arg.id),
        OutboundCmd::Update(args) => update(ctx, &args).map(|v| ctx.out.value(&v)),
        OutboundCmd::Replace(args) => replace::run(ctx, &args),
        OutboundCmd::Test(arg) => object_action(ctx, Op::Test, &arg.id).map(|v| ctx.out.value(&v)),
        OutboundCmd::Delete(args) => delete_object(ctx, &WEBHOOK_OUTBOUND, &args, |_| message("consequence_webhook_outbound", &[])),
    }
}

/// Create, publish, mint the signing secret (revealed once), read.
pub fn create(ctx: &mut Ctx, args: &OutboundCreate) -> Result<Value, Fail> {
    let body = json!({ "url": args.url, "event_types": args.event });
    // The verb takes no name, so the object is named by its URL, cut to the
    // 80 characters a platform object name may hold (16.10.26v).
    let name: String = args.url.chars().take(80).collect();
    let mut made = Creation::default();
    let result = made.object(ctx, &WEBHOOK_OUTBOUND, &name, Some(&args.client), body).and_then(|hook| {
        let id = hook["id"].as_str().unwrap_or_default().to_string();
        made.secret(ctx, &WEBHOOK_OUTBOUND, &id)?;
        Ok((WEBHOOK_OUTBOUND, id))
    });
    made.conclude(ctx, result)
}

/// `enable`, `disable` or `test`: object-level, never versioned.
fn object_action(ctx: &mut Ctx, op: Op, id: &str) -> Result<Value, Fail> {
    let route = object(WEBHOOK_OUTBOUND.word, op);
    ctx.call(&Request { route: &route, vars: &[("id", id)], query: &[], body: crate::http::Body::None })
}

/// `--url`/`--event` are a body update; `--enable`/`--disable` follow it.
pub fn update(ctx: &mut Ctx, args: &OutboundUpdate) -> Result<Value, Fail> {
    let mut last: Option<Value> = None;
    if args.url.is_some() || !args.event.is_empty() {
        let overlay = |body: &mut Value| {
            if let Some(url) = &args.url {
                body["url"] = json!(url);
            }
            if !args.event.is_empty() {
                body["event_types"] = json!(args.event);
            }
        };
        last = Some(objects::body_update(ctx, &WEBHOOK_OUTBOUND, &args.id, &overlay)?);
    }
    if args.enable || args.disable {
        last = Some(object_action(ctx, if args.enable { Op::Enable } else { Op::Disable }, &args.id)?);
    }
    last.ok_or_else(|| Fail::usage("nothing to update: pass --url, --event, --enable or --disable"))
}
