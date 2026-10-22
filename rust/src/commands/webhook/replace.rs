//! `webhook outbound replace` (D1, D5): a new endpoint with a new signing
//! secret, then the old one deleted. Both prompts, or the `--confirm`
//! check, complete **before** the new endpoint's first write, so a decline
//! sends neither the create nor the delete. If the create lands and the
//! delete is refused, the new secret is still revealed and both ids named.

use serde_json::Value;

use super::outbound;
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::webhook::{OutboundCreate, OutboundReplace};
use crate::commands::deletes::prompt;
use crate::commands::objects::{self, WEBHOOK_OUTBOUND};
use crate::confirm::confirm_destructive;
use crate::exit::{Fail, Made};
use crate::http::routes::Op;

/// The old object and its serving version's body: the lookups the new
/// endpoint's client and any omitted flag come from.
fn lookups(ctx: &mut Ctx, id: &str) -> Result<(Value, Value), Fail> {
    let old = objects::get(ctx, &WEBHOOK_OUTBOUND, id)?;
    let versions = old["versions"].as_array().cloned().unwrap_or_default();
    let semver = old["serving_version"].as_str().or_else(|| versions.first().and_then(|v| v["semver"].as_str())).unwrap_or("").to_string();
    let version = objects::version_op(ctx, &WEBHOOK_OUTBOUND, Op::VersionGet, id, &semver)?;
    Ok((old, version["body"].clone()))
}

pub fn run(ctx: &mut Ctx, args: &OutboundReplace) -> Result<(), Fail> {
    let mut looked: Option<(Value, Value)> = None;
    let email = confirm_destructive(ctx, &args.confirm, &mut |ctx| {
        let (old, body) = lookups(ctx, &args.id)?;
        let p = prompt(&WEBHOOK_OUTBOUND, &old, &args.id, None, |_| message("consequence_replace", &[]));
        looked = Some((old, body));
        Ok(p)
    })?;
    let (old, body) = match looked {
        Some(found) => found,
        None => lookups(ctx, &args.id)?,
    };
    let strings = |v: &Value| v.as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect()).unwrap_or_default();
    let create = OutboundCreate {
        client: old["parent_id"].as_str().unwrap_or_default().to_string(),
        url: args.url.clone().unwrap_or_else(|| body["url"].as_str().unwrap_or_default().to_string()),
        event: if args.event.is_empty() { strings(&body["event_types"]) } else { args.event.clone() },
    };
    let new = outbound::create(ctx, &create)?;
    objects::delete(ctx, &WEBHOOK_OUTBOUND, &args.id, None, &email).map_err(|mut fail| {
        let new_id = new["id"].as_str().unwrap_or_default().to_string();
        fail.made = vec![Made { kind: "webhook_outbound".into(), id: new_id }];
        fail.hint(format!("the old endpoint {} still delivers; delete it with `lingara webhook outbound delete {}`", args.id, args.id))
    })?;
    Ok(())
}
