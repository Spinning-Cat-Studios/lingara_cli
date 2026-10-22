//! `lingara oauth_client …` (D1, D2). Rotation is two commands, because it
//! is two calls: `secret create` while the old secret stays live, then
//! `secret delete` once the new one is deployed.

use serde_json::{Value, json};

use super::creates::Creation;
use super::deletes::{self, delete_object};
use super::objects::{self, OAUTH_CLIENT};
use super::{Columns, print_list};
use crate::app::Ctx;
use crate::assets::message;
use crate::auth::SCOPES;
use crate::cli::client::{ClientCmd, CreateArgs, SecretCmd, SecretDeleteArgs, UpdateArgs};
use crate::confirm::{Prompt, confirm_destructive};
use crate::exit::Fail;
use crate::http::Body;
use crate::http::routes::{Op, object};

const COLUMNS: Columns = &[("ID", "id"), ("NAME", "name"), ("TYPE", "client_type"), ("SCOPES", "allowed_scopes"), ("CREATED", "created_at")];

pub fn run(ctx: &mut Ctx, cmd: ClientCmd) -> Result<(), Fail> {
    match cmd {
        ClientCmd::Create(args) => create(ctx, &args).map(|_| ()),
        ClientCmd::List => print_list(ctx, &OAUTH_CLIENT, COLUMNS),
        ClientCmd::Get(arg) => objects::print_get(ctx, &OAUTH_CLIENT, &arg.id),
        ClientCmd::Update(args) => update(ctx, &args),
        ClientCmd::Delete(args) => delete_object(ctx, &OAUTH_CLIENT, &args, consequence),
        ClientCmd::Secret(SecretCmd::Create(args)) => secret_create(ctx, &args.client_id),
        ClientCmd::Secret(SecretCmd::Delete(args)) => secret_delete(ctx, &args),
    }
}

/// The client's version body (16.10.26v §1). `--ci` is confidential with the
/// four scopes; whether the client may be one is the server's predicate.
pub fn body(args: &CreateArgs) -> Value {
    let (client_type, scopes): (&str, Vec<String>) = if args.ci {
        ("confidential", SCOPES.split(' ').map(str::to_string).collect())
    } else {
        (&args.client_type, args.scope.clone())
    };
    json!({ "client_type": client_type, "allowed_scopes": scopes, "billing_mode": args.billing_mode, "redirect_uris": args.redirect_uri })
}

/// Create, publish, mint the confidential client's one-time secret, read.
pub fn create(ctx: &mut Ctx, args: &CreateArgs) -> Result<Value, Fail> {
    let mut made = Creation::default();
    let confidential = args.ci || args.client_type == "confidential";
    let result = made.object(ctx, &OAUTH_CLIENT, &args.name, None, body(args)).and_then(|client| {
        let id = client["id"].as_str().unwrap_or_default().to_string();
        if confidential {
            made.secret(ctx, &OAUTH_CLIENT, &id)?;
        }
        Ok((OAUTH_CLIENT, id))
    });
    made.conclude(ctx, result)
}

/// `--name` is a rename; any other flag is a body update.
pub fn update(ctx: &mut Ctx, args: &UpdateArgs) -> Result<(), Fail> {
    let mut last = None;
    if let Some(name) = &args.name {
        last = Some(objects::rename(ctx, &OAUTH_CLIENT, &args.id, name)?);
    }
    if !args.scope.is_empty() || args.api_version.is_some() || !args.redirect_uri.is_empty() {
        let overlay = |body: &mut Value| {
            if !args.scope.is_empty() {
                body["allowed_scopes"] = json!(args.scope);
            }
            if let Some(v) = &args.api_version {
                body["api_version"] = json!(v);
            }
            if !args.redirect_uri.is_empty() {
                body["redirect_uris"] = json!(args.redirect_uri);
            }
        };
        last = Some(objects::body_update(ctx, &OAUTH_CLIENT, &args.id, &overlay)?);
    }
    let value = last.ok_or_else(|| Fail::usage("nothing to update: pass --name, --scope, --api-version or --redirect-uri"))?;
    ctx.out.value(&value);
    Ok(())
}

/// The children 16.10.26v §7's cascade deletes, counted by kind.
fn consequence(object: &Value) -> String {
    let children = object["children"].as_array().cloned().unwrap_or_default();
    let count = |kinds: &[&str]| children.iter().filter(|c| kinds.contains(&c["kind"].as_str().unwrap_or(""))).count().to_string();
    let vars = [("apps", count(&["app"])), ("embeds", count(&["embed"])), ("webhooks", count(&["webhook_in", "webhook_out"]))];
    message("consequence_oauth_client", &vars.iter().map(|(k, v)| (*k, v.as_str())).collect::<Vec<_>>())
}

fn secret_create(ctx: &mut Ctx, client_id: &str) -> Result<(), Fail> {
    let minted = objects::secret_create(ctx, &OAUTH_CLIENT, client_id)?;
    ctx.out.value(&minted);
    ctx.out.note("The secret is shown once: store it now. It cannot be read again.");
    Ok(())
}

/// A secret has no read of its own, so the prompt names the client's.
fn secret_delete(ctx: &mut Ctx, args: &SecretDeleteArgs) -> Result<(), Fail> {
    let email = confirm_destructive(ctx, &args.confirm, &mut |ctx| {
        let client = objects::get(ctx, &OAUTH_CLIENT, &args.client_id)?;
        let mut prompt: Prompt = deletes::prompt(&OAUTH_CLIENT, &client, &args.secret_id, None, |_| message("consequence_secret", &[]));
        (prompt.kind, prompt.pinned_by) = ("secret", 0);
        Ok(prompt)
    })?;
    let route = object(OAUTH_CLIENT.word, Op::SecretDelete);
    let vars = [("id", args.client_id.as_str()), ("secret_id", args.secret_id.as_str())];
    let request = crate::http::Request { route: &route, vars: &vars, query: &[], body: Body::Json(json!({ "confirm": email })) };
    ctx.call(&request)?;
    ctx.out.nothing();
    Ok(())
}
