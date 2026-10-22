//! `lingara webhook inbound|outbound …` (D1), the shape the release plan
//! asked for, plus `replace` and `test` on outbound.

use clap::{Args, Subcommand};

use super::{ConfirmArgs, DeleteArgs, IdArg};

#[derive(Subcommand, Debug)]
pub enum WebhookCmd {
    /// Inbound webhooks: a confidential client a sender uses for POST /v1/events.
    #[command(subcommand)]
    Inbound(InboundCmd),
    /// Outbound webhooks: events Lingara delivers to your endpoint, signed.
    #[command(subcommand)]
    Outbound(OutboundCmd),
}

#[derive(Subcommand, Debug)]
pub enum InboundCmd {
    /// Create an inbound webhook and its client, and show the client's secret once.
    Create(InboundCreate),
    /// List your inbound webhooks.
    List,
    /// Show one inbound webhook.
    Get(IdArg),
    /// Rename an inbound webhook, or replace its event types.
    Update(InboundUpdate),
    /// Delete an inbound webhook, or one version. Its client stays.
    Delete(DeleteArgs),
}

#[derive(Args, Debug)]
pub struct InboundCreate {
    /// The webhook's name
    #[arg(long)]
    pub name: String,
    /// An inbound event type it accepts; absent, every one
    #[arg(long, value_name = "type")]
    pub event: Vec<String>,
}

#[derive(Args, Debug)]
pub struct InboundUpdate {
    pub id: String,
    /// The new name
    #[arg(long)]
    pub name: Option<String>,
    /// Replaces the event types
    #[arg(long, value_name = "type")]
    pub event: Vec<String>,
}

#[derive(Subcommand, Debug)]
pub enum OutboundCmd {
    /// Create an outbound webhook, and show its signing secret once.
    Create(OutboundCreate),
    /// List your outbound webhooks.
    List,
    /// Show one outbound webhook.
    Get(IdArg),
    /// Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.
    Update(OutboundUpdate),
    /// Start again: a new endpoint with a new signing secret, then the old one deleted.
    Replace(OutboundReplace),
    /// Send a test delivery.
    Test(IdArg),
    /// Delete an outbound webhook, or one version.
    Delete(DeleteArgs),
}

#[derive(Args, Debug)]
pub struct OutboundCreate {
    /// The OAuth client it belongs to
    #[arg(long, value_name = "id")]
    pub client: String,
    /// The endpoint deliveries go to
    #[arg(long, value_name = "url")]
    pub url: String,
    /// An event type to deliver
    #[arg(long, value_name = "type", required = true)]
    pub event: Vec<String>,
}

#[derive(Args, Debug, Default)]
pub struct OutboundUpdate {
    pub id: String,
    /// The new endpoint
    #[arg(long, value_name = "url")]
    pub url: Option<String>,
    /// Replaces the event types
    #[arg(long, value_name = "type")]
    pub event: Vec<String>,
    /// Resume deliveries
    #[arg(long, conflicts_with = "disable")]
    pub enable: bool,
    /// Pause deliveries
    #[arg(long)]
    pub disable: bool,
}

#[derive(Args, Debug)]
pub struct OutboundReplace {
    pub id: String,
    /// The new endpoint; absent, the old one's
    #[arg(long, value_name = "url")]
    pub url: Option<String>,
    /// The new event types; absent, the old one's
    #[arg(long, value_name = "type")]
    pub event: Vec<String>,
    #[command(flatten)]
    pub confirm: ConfirmArgs,
}
