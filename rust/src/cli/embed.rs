//! `lingara embed …` (D1, D2): an embed and the metered client behind it.

use clap::{Args, Subcommand};

use super::{DeleteArgs, IdArg};

#[derive(Subcommand, Debug)]
pub enum EmbedCmd {
    /// Create an embed and its metered OAuth client, and show the client's secret once.
    Create(CreateArgs),
    /// List your embeds.
    List,
    /// Show one embed.
    Get(IdArg),
    /// Rename an embed, or replace its origins.
    Update(UpdateArgs),
    /// Delete an embed and its OAuth client, or one version.
    Delete(DeleteArgs),
}

#[derive(Args, Debug)]
pub struct CreateArgs {
    /// The embed's name
    #[arg(long)]
    pub name: String,
    /// A web origin allowed to use it (up to 10)
    #[arg(long, value_name = "origin")]
    pub origin: Vec<String>,
    /// A scope beside embed:mint
    #[arg(long, value_name = "scope")]
    pub scope: Vec<String>,
}

#[derive(Args, Debug)]
pub struct UpdateArgs {
    pub id: String,
    /// The new name
    #[arg(long)]
    pub name: Option<String>,
    /// Replaces the allowed origins
    #[arg(long, value_name = "origin")]
    pub origin: Vec<String>,
}
