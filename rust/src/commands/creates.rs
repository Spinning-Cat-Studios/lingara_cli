//! A create that may take several calls (D1, D2). It ends with one
//! `<word>.get`, printed with any secret it minted. Failing part-way, it
//! still reveals that secret on stdout and names every object it made on
//! stderr, then exits with the failing call's code. Nothing is rolled back.

use serde_json::{Value, json};

use super::objects::{self, Kind};
use crate::app::Ctx;
use crate::exit::{Fail, Made};
use crate::output::table;

#[derive(Default)]
pub struct Creation {
    pub made: Vec<Made>,
    /// `{id, value}`, the D6 member a create's JSON gains.
    pub secret: Option<Value>,
}

impl Creation {
    /// One object: created, named as made, then published when private.
    pub fn object(&mut self, ctx: &mut Ctx, k: &Kind, name: &str, parent: Option<&str>, body: Value) -> Result<Value, Fail> {
        let created = objects::create(ctx, k, name, parent, body)?;
        let id = created["id"].as_str().unwrap_or_default().to_string();
        self.made.push(Made { kind: k.word.to_string(), id });
        let semver = created["version"]["semver"].as_str().unwrap_or_default().to_string();
        objects::publish_if_private(ctx, k, &created, &semver)?;
        Ok(created)
    }

    /// The one-time secret. `409 object_projection_pending` means the
    /// client is still publishing (16.10.26v §5): `refused`, with a retry hint.
    pub fn secret(&mut self, ctx: &mut Ctx, k: &Kind, id: &str) -> Result<(), Fail> {
        let minted = objects::secret_create(ctx, k, id).map_err(|f| pending_hint(f, id))?;
        self.secret = Some(json!({ "id": minted["secret_id"], "value": minted["secret"] }));
        Ok(())
    }

    /// The final read and its printing, or the failure with what was made.
    pub fn conclude(self, ctx: &mut Ctx, result: Result<(Kind, String), Fail>) -> Result<Value, Fail> {
        match result.and_then(|(k, id)| objects::get(ctx, &k, &id)) {
            Ok(object) => {
                self.print(ctx, Some(&object));
                Ok(object)
            }
            Err(mut fail) => {
                self.print(ctx, None);
                fail.made = self.made;
                Err(fail)
            }
        }
    }

    fn print(&self, ctx: &mut Ctx, object: Option<&Value>) {
        if object.is_none() && self.secret.is_none() {
            return;
        }
        if ctx.out.json {
            let mut value = object.cloned().unwrap_or_else(|| json!({}));
            if let Some(secret) = &self.secret {
                value["secret"] = secret.clone();
            }
            ctx.out.json_value(&value);
        } else {
            let mut rows = object.map(table::fields).unwrap_or_default();
            if let Some(secret) = &self.secret {
                rows.push(vec!["SECRET".into(), table::cell(&secret["value"])]);
            }
            ctx.out.print(&table::render(&["FIELD", "VALUE"], &rows));
        }
        if self.secret.is_some() {
            ctx.out.note("The secret is shown once: store it now. It cannot be read again.");
        }
    }
}

fn pending_hint(fail: Fail, id: &str) -> Fail {
    if fail.code == "object_projection_pending" {
        return fail.hint(format!("the client is still publishing; run `lingara client secret create {id}` in a moment"));
    }
    fail
}
