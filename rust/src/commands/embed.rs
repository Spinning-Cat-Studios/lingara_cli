//! `lingara embed …` (D2): an `embed` object (body `{origins[]}`) on a
//! metered, confidential client, which holds `embed:mint` through the
//! embed's projection, never through its own body (16.10.26v §1).

use serde_json::{Value, json};

use super::creates::Creation;
use super::deletes::{delete_object, parent};
use super::objects::{self, EMBED, OAUTH_CLIENT};
use super::{Columns, print_list};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::embed::{CreateArgs, EmbedCmd, UpdateArgs};
use crate::exit::Fail;

const COLUMNS: Columns = &[("ID", "id"), ("NAME", "name"), ("CLIENT", "parent_id"), ("ORIGINS", "origins"), ("SERVING", "serving_version")];

pub fn run(ctx: &mut Ctx, cmd: EmbedCmd) -> Result<(), Fail> {
    match cmd {
        EmbedCmd::Create(args) => create(ctx, &args),
        EmbedCmd::List => print_list(ctx, &EMBED, COLUMNS),
        EmbedCmd::Get(arg) => objects::print_get(ctx, &EMBED, &arg.id),
        EmbedCmd::Update(args) => update(ctx, &args),
        EmbedCmd::Delete(args) => delete_object(ctx, &EMBED, &args, |o| message("consequence_embed", &[("client", &parent(o))])),
    }
}

/// The client first (confidential, metered), its secret, then the embed.
fn create(ctx: &mut Ctx, args: &CreateArgs) -> Result<(), Fail> {
    let client_body = json!({ "client_type": "confidential", "billing_mode": "metered", "allowed_scopes": args.scope });
    let mut made = Creation::default();
    let result = made.object(ctx, &OAUTH_CLIENT, &args.name, None, client_body).and_then(|client| {
        let client_id = client["id"].as_str().unwrap_or_default().to_string();
        made.secret(ctx, &OAUTH_CLIENT, &client_id)?;
        let embed = made.object(ctx, &EMBED, &args.name, Some(&client_id), json!({ "origins": args.origin }))?;
        Ok((EMBED, embed["id"].as_str().unwrap_or_default().to_string()))
    });
    made.conclude(ctx, result).map(|_| ())
}

/// `--name` renames; `--origin` replaces the origins in a body update.
fn update(ctx: &mut Ctx, args: &UpdateArgs) -> Result<(), Fail> {
    let mut last: Option<Value> = None;
    if let Some(name) = &args.name {
        last = Some(objects::rename(ctx, &EMBED, &args.id, name)?);
    }
    if !args.origin.is_empty() {
        last = Some(objects::body_update(ctx, &EMBED, &args.id, &|body| body["origins"] = json!(args.origin))?);
    }
    let value = last.ok_or_else(|| Fail::usage("nothing to update: pass --name or --origin"))?;
    ctx.out.value(&value);
    Ok(())
}
