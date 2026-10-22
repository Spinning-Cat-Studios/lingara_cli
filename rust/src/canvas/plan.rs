//! D7 step 2: the web planner's rules (`plan.ts`) over field nodes.
//!
//! Ops come out in topological order and every client reference is a
//! backward `{handle}`. "Still live" means live **and** still on the
//! recorded client: a recorded client is live when the client list has a
//! row with its `runtime_id`; a webhook or app when its list has its
//! `runtime_id` under that client's platform object; a secret when its
//! client is (the platform lists carry no secrets, and a recorded secret is
//! never re-planned). A node with `custom: true` runs a script, which only
//! the browser's sandbox may do, so it refuses the plan by its handle.

use std::collections::BTreeMap;

use serde_json::{Value, json};

use super::definition::{Definition, Node};
use super::spec;

/// Node handle → the runtime id an earlier apply recorded for it.
pub type Records = BTreeMap<String, String>;

/// One `/platform/objects` list row, as the planner joins it.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Row {
    pub id: String,
    pub runtime_id: String,
    pub parent_id: String,
}

impl Row {
    pub fn read(v: &Value) -> Row {
        let text = |k: &str| v[k].as_str().unwrap_or_default().to_string();
        Row { id: text("id"), runtime_id: text("runtime_id"), parent_id: text("parent_id") }
    }
}

/// The live `oauth_client`, `webhook_out` and `app` lists (D7 step 1).
#[derive(Debug, Clone, Default)]
pub struct Live {
    pub clients: Vec<Row>,
    pub webhooks: Vec<Row>,
    pub apps: Vec<Row>,
}

impl Live {
    pub fn client_by_runtime(&self, runtime_id: &str) -> Option<&Row> {
        self.clients.iter().find(|r| r.runtime_id == runtime_id)
    }

    /// The platform object id behind a recorded runtime id of `kind`.
    pub fn platform_id(&self, kind: &str, runtime_id: &str) -> Option<String> {
        let rows = match kind {
            "client" => &self.clients,
            "webhook" => &self.webhooks,
            _ => &self.apps,
        };
        rows.iter().find(|r| r.runtime_id == runtime_id).map(|r| r.id.clone())
    }
}

/// `{handle, kind, op, client?: {handle}, id?, body?}`, the dry-run's shape.
#[derive(Debug, Clone, PartialEq)]
pub struct PlanOp {
    pub handle: String,
    pub kind: String,
    pub op: &'static str,
    pub client: Option<String>,
    pub id: Option<String>,
    pub body: Option<Value>,
}

impl PlanOp {
    pub fn wire(&self) -> Value {
        let mut v = json!({ "handle": self.handle, "kind": self.kind, "op": self.op });
        if let Some(c) = &self.client {
            v["client"] = json!({ "handle": c });
        }
        if let Some(id) = &self.id {
            v["id"] = json!(id);
        }
        if let Some(body) = &self.body {
            v["body"] = body.clone();
        }
        v
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Refusal {
    Script(String),
    Fields(String, String),
    NoClient(String),
    Cycle,
    /// An app node whose manifest's render URL is a quick tunnel or
    /// `dev.invalid` (16.10.26ad D7 step 2, 16.10.26ae D10 row 15).
    Ephemeral(String),
}

/// The recorded entries still live by the rule above.
pub fn still_live(def: &Definition, recorded: &Records, live: &Live) -> Records {
    let mut kept = Records::new();
    for node in def.topo_order().unwrap_or_default() {
        let Some(record) = recorded.get(&node.id) else { continue };
        let keep = if node.kind == "client" {
            live.client_by_runtime(record).is_some()
        } else {
            let client = def.client_of(&node.id).and_then(|h| kept.get(h)).and_then(|r| live.client_by_runtime(r));
            client.is_some_and(|c| dependant_live(node, record, &c.id, live))
        };
        if keep {
            kept.insert(node.id.clone(), record.clone());
        }
    }
    kept
}

fn dependant_live(node: &Node, record: &str, client_platform_id: &str, live: &Live) -> bool {
    let on_client = |rows: &[Row]| rows.iter().any(|r| r.runtime_id == record && r.parent_id == client_platform_id);
    match node.kind.as_str() {
        "secret" => true,
        "webhook" => on_client(&live.webhooks),
        _ => on_client(&live.apps),
    }
}

fn body_for(node: &Node, create: bool) -> Result<Option<Value>, Refusal> {
    let fields = &node.fields;
    let spelled = match node.kind.as_str() {
        "client" => spec::client(fields, create),
        "webhook" => spec::webhook(fields),
        "app" => spec::app(fields),
        _ => return Ok(None),
    };
    let body = spelled.map_err(|detail| Refusal::Fields(node.id.clone(), detail))?;
    let render_url = body["manifest"]["render_url"].as_str().unwrap_or_default();
    if node.kind == "app" && crate::commands::apps::manifest::ephemeral(render_url) {
        return Err(Refusal::Ephemeral(node.id.clone()));
    }
    Ok(Some(body))
}

/// D7 step 2: every field node's op, in plan order, over the kept records.
pub fn build(def: &Definition, kept: &Records) -> Result<Vec<PlanOp>, Refusal> {
    let order = def.topo_order().ok_or(Refusal::Cycle)?;
    if let Some(scripted) = order.iter().find(|n| n.custom) {
        return Err(Refusal::Script(scripted.id.clone()));
    }
    let mut ops = Vec::new();
    for node in order {
        let record = kept.get(&node.id);
        if record.is_some() && node.kind == "secret" {
            continue;
        }
        let client = if node.kind == "client" { None } else { Some(def.client_of(&node.id).ok_or_else(|| Refusal::NoClient(node.id.clone()))?) };
        let op = if record.is_none() { "create" } else { "update" };
        ops.push(PlanOp {
            handle: node.id.clone(),
            kind: node.kind.clone(),
            op,
            client: client.filter(|_| op == "create").map(str::to_string),
            id: record.cloned(),
            body: body_for(node, op == "create")?,
        });
    }
    Ok(ops)
}
