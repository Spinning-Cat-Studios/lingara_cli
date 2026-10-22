//! `lingara oauth_client …`, alias `client` (D1).

use clap::{Args, Subcommand};

use super::{ConfirmArgs, DeleteArgs, IdArg};

#[derive(Subcommand, Debug)]
pub enum ClientCmd {
    /// Create an OAuth client; a confidential one's secret is shown once.
    Create(CreateArgs),
    /// List your OAuth clients.
    List,
    /// Show one OAuth client.
    Get(IdArg),
    /// Rename an OAuth client, or replace its scopes, API version or redirect URIs.
    Update(UpdateArgs),
    /// Delete an OAuth client and everything under it, or one version.
    Delete(DeleteArgs),
    /// A client's secrets. Rotate by creating the new one, deploying it, then deleting the old one.
    #[command(subcommand)]
    Secret(SecretCmd),
}

#[derive(Args, Debug, Default)]
pub struct CreateArgs {
    /// The client's name
    #[arg(long)]
    pub name: String,
    /// A scope the client may ask for
    #[arg(long, value_name = "scope")]
    pub scope: Vec<String>,
    /// Whether the client keeps a secret
    #[arg(long = "type", value_parser = ["confidential", "public"], default_value = "confidential")]
    pub client_type: String,
    /// How its usage is billed
    #[arg(long, value_name = "mode", value_parser = ["allowance", "metered"], default_value = "allowance")]
    pub billing_mode: String,
    /// A redirect URI it may use
    #[arg(long, value_name = "uri")]
    pub redirect_uri: Vec<String>,
    /// A CI client: confidential, holding the four platform and canvas scopes
    #[arg(long, conflicts_with_all = ["client_type", "scope"])]
    pub ci: bool,
}

#[derive(Args, Debug, Default)]
pub struct UpdateArgs {
    pub id: String,
    /// The new name
    #[arg(long)]
    pub name: Option<String>,
    /// Replaces the scopes
    #[arg(long, value_name = "scope")]
    pub scope: Vec<String>,
    /// The API version it is pinned to
    #[arg(long, value_name = "version")]
    pub api_version: Option<String>,
    /// Replaces the redirect URIs
    #[arg(long, value_name = "uri")]
    pub redirect_uri: Vec<String>,
}

#[derive(Subcommand, Debug)]
pub enum SecretCmd {
    /// Mint a new secret, shown once. The old one stays live.
    Create(SecretCreateArgs),
    /// Revoke a secret.
    Delete(SecretDeleteArgs),
}

#[derive(Args, Debug)]
pub struct SecretCreateArgs {
    pub client_id: String,
}

#[derive(Args, Debug)]
pub struct SecretDeleteArgs {
    pub client_id: String,
    pub secret_id: String,
    #[command(flatten)]
    pub confirm: ConfirmArgs,
}
