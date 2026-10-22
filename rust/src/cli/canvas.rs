//! `lingara canvas …` (D1, D7): content through `/v1/canvases`, lifecycle
//! through `/platform/objects`.

use std::path::PathBuf;

use clap::{Args, Subcommand, ValueHint};

use super::{DeleteArgs, IdArg};

#[derive(Subcommand, Debug)]
pub enum CanvasCmd {
    /// Create a canvas, from a definition file or empty.
    Create(CreateArgs),
    /// List your canvases.
    List,
    /// Show one canvas.
    Get(IdArg),
    /// Rename a canvas.
    Update(UpdateArgs),
    /// Save a definition as the canvas's draft. Creates nothing else.
    Draft(DraftArgs),
    /// Create the canvas's objects by running this CLI's own commands, then activate it.
    Apply(ApplyArgs),
    /// Delete a canvas, or one version. The objects it made stay.
    Delete(DeleteArgs),
}

#[derive(Args, Debug)]
pub struct CreateArgs {
    /// The canvas's name
    #[arg(long)]
    pub name: Option<String>,
    /// The definition; absent, an empty one
    #[arg(long, value_name = "definition.json", value_hint = ValueHint::FilePath)]
    pub file: Option<PathBuf>,
}

#[derive(Args, Debug)]
pub struct UpdateArgs {
    pub id: String,
    /// The new name
    #[arg(long)]
    pub name: String,
}

#[derive(Args, Debug)]
pub struct DraftArgs {
    pub id: String,
    /// The definition
    #[arg(long, value_name = "definition.json", value_hint = ValueHint::FilePath)]
    pub file: PathBuf,
}

#[derive(Args, Debug)]
pub struct ApplyArgs {
    pub id: String,
    /// Print what each object would do, and write nothing
    #[arg(long, conflicts_with = "print_commands")]
    pub dry_run: bool,
    /// Print the commands apply would run, and write nothing
    #[arg(long)]
    pub print_commands: bool,
}
