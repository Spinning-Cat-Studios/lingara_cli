//! Every object `delete` (D5): the ritual over one `<word>.get` for the
//! prompt, then the destructive request with the canonical email.

use serde_json::Value;

use super::objects::{self, Kind};
use crate::app::Ctx;
use crate::assets::message;
use crate::cli::DeleteArgs;
use crate::confirm::{Prompt, confirm_destructive};
use crate::exit::Fail;

/// The kind's `consequence_*` line, from the object's read.
pub type Consequence = fn(&Value) -> String;

/// `{client}` is the read's `parent_id`, the client's platform object id:
/// the one `lingara client delete <id>` takes, never the public `client_id`.
pub fn parent(object: &Value) -> String {
    object["parent_id"].as_str().unwrap_or("-").to_string()
}

pub fn prompt(k: &Kind, object: &Value, id: &str, version: Option<&str>, consequence: Consequence) -> Prompt {
    Prompt {
        kind: k.label,
        name: object["name"].as_str().unwrap_or(id).to_string(),
        id: id.to_string(),
        consequence: version.map_or_else(|| consequence(object), |s| message("consequence_version", &[("semver", s)])),
        pinned_by: object["pinned_by"].as_u64().unwrap_or(0),
    }
}

pub fn delete_object(ctx: &mut Ctx, k: &Kind, args: &DeleteArgs, consequence: Consequence) -> Result<(), Fail> {
    let version = args.version.as_deref();
    let email = confirm_destructive(ctx, &args.confirm, &mut |ctx| {
        let object = objects::get(ctx, k, &args.id)?;
        Ok(prompt(k, &object, &args.id, version, consequence))
    })?;
    objects::delete(ctx, k, &args.id, version, &email)?;
    ctx.out.nothing();
    Ok(())
}
