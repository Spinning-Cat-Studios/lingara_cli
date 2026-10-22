//! The clap tree (ADR 16.10.26ad D9): `spec/cli.schema.json`'s commands,
//! args and flags, which `make check-tree` diffs against `lingara __tree`.
//! Summaries are the schema's, as doc comments. A path flag carries
//! `ValueHint::FilePath`, which is how `__tree` reports it as `path`.

pub mod apps;
pub mod canvas;
pub mod client;
pub mod embed;
pub mod webhook;

use clap::{Args, Parser, Subcommand};

#[derive(Parser, Debug)]
#[command(name = "lingara", version = concat!(env!("CARGO_PKG_VERSION"), " (rust)"))]
#[command(about = "lingara, the Lingara developer CLI")]
pub struct Root {
    /// Machine-readable output on stdout
    #[arg(long, global = true)]
    pub json: bool,
    /// The stored credentials to use
    #[arg(long, global = true, value_name = "name")]
    pub profile: Option<String>,
    #[command(subcommand)]
    pub command: Top,
}

#[derive(Subcommand, Debug)]
pub enum Top {
    /// Sign in through the browser, or with a device code.
    Login(LoginArgs),
    /// Revoke and forget the stored credentials.
    Logout,
    /// Show the developer the credentials belong to.
    Whoami,
    /// Apps that render cards inside Lingara.
    #[command(subcommand)]
    Apps(apps::AppsCmd),
    /// Lingara inside your game or website: an embed and the metered OAuth client behind it.
    #[command(subcommand)]
    Embed(embed::EmbedCmd),
    /// OAuth clients: the credentials your servers, apps and CI use.
    #[command(name = "oauth_client", visible_alias = "client", subcommand)]
    OauthClient(client::ClientCmd),
    /// Webhooks: events your game sends in, and events Lingara sends out.
    #[command(subcommand)]
    Webhook(webhook::WebhookCmd),
    /// Canvases: builder flows that create their objects only when applied.
    #[command(subcommand)]
    Canvas(canvas::CanvasCmd),
    /// The registered command tree as JSON, for `make check-tree`.
    #[command(name = "__tree", hide = true)]
    Tree,
}

#[derive(Args, Debug)]
pub struct LoginArgs {
    /// Sign in with a code shown here, for a machine with no browser
    #[arg(long)]
    pub device: bool,
}

/// The `confirm` flag group every dangerous command carries (D5). Neither
/// flag reads the environment or configuration.
#[derive(Args, Debug, Clone, Default)]
pub struct ConfirmArgs {
    /// Answer the y/n question; needs --confirm too
    #[arg(short = 'y', long = "yes")]
    pub yes: bool,
    /// Your developer email, typed back; needs -y too
    #[arg(long, value_name = "email")]
    pub confirm: Option<String>,
}

#[derive(Args, Debug)]
pub struct IdArg {
    pub id: String,
}

/// Every object `delete`: the id, an optional version, the ritual.
#[derive(Args, Debug)]
pub struct DeleteArgs {
    pub id: String,
    /// Delete only this version
    #[arg(long, value_name = "semver")]
    pub version: Option<String>,
    #[command(flatten)]
    pub confirm: ConfirmArgs,
}
