//! `lingara apps …` (16.10.26ad D1; 16.10.26ae D1, D6, D7). `create` and
//! `update` each carry both forms, the registration one (`--manifest`) and
//! the project one; their one dispatch point is `commands::apps`.

use std::path::PathBuf;

use clap::{Args, Subcommand, ValueEnum, ValueHint};

use super::{DeleteArgs, IdArg};

#[derive(Subcommand, Debug)]
pub enum AppsCmd {
    /// Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.
    Create(CreateArgs),
    /// List your apps, or the apps platform.toml pins.
    List(ListArgs),
    /// Show one app.
    Get(IdArg),
    /// Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file.
    Update(UpdateArgs),
    /// Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.
    Dev(DevArgs),
    /// Show where the app's dev version renders from: live, parked, or the serving version.
    Status(StatusArgs),
    /// Add an app to platform.toml (and its example), by id or by name.
    Pin(PinArgs),
    /// Remove an app's row from platform.toml; the example changes by review.
    Unpin(UnpinArgs),
    /// Delete an app, or one of its versions.
    Delete(DeleteArgs),
    /// Submit a version of an app for review, to make it public.
    Submit(SubmitArgs),
    /// An app's versions.
    #[command(subcommand)]
    Versions(VersionsCmd),
}

/// `--config`: which platform.toml, before `LINGARA_PLATFORM_TOML`.
#[derive(Args, Debug, Clone, Default)]
pub struct ConfigArg {
    /// The platform.toml to read; default the nearest at or above this directory
    #[arg(long, value_name = "path", value_hint = ValueHint::FilePath)]
    pub config: Option<PathBuf>,
}

#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lang {
    Typescript,
    Rust,
    Go,
    Java,
    Kotlin,
    Ruby,
    Php,
}

#[derive(Args, Debug)]
pub struct CreateArgs {
    pub name: String,
    /// The OAuth client's id (with --manifest)
    #[arg(long, value_name = "id", conflicts_with_all = ["lang", "bare", "dir"])]
    pub client: Option<String>,
    /// The app's manifest file (with --client)
    #[arg(long, value_name = "path", value_hint = ValueHint::FilePath,
          conflicts_with_all = ["lang", "bare", "dir", "no_register", "description", "port", "slot", "context"])]
    pub manifest: Option<PathBuf>,
    /// The app's language
    #[arg(long, value_enum, value_name = "app-kit-lang")]
    pub lang: Option<Lang>,
    /// The manifest as code in the app's language, and a handler on the kit's primitives
    #[arg(long)]
    pub bare: bool,
    /// Where to write the project; default ./<name>
    #[arg(long, value_name = "path", value_hint = ValueHint::FilePath)]
    pub dir: Option<PathBuf>,
    /// Scaffold only; register later with apps update
    #[arg(long = "no-register")]
    pub no_register: bool,
    /// One sentence about the app
    #[arg(long, value_name = "text")]
    pub description: Option<String>,
    /// The port the app listens on; default 8787
    #[arg(long, value_name = "n", value_parser = clap::value_parser!(u16).range(1..))]
    pub port: Option<u16>,
    /// Where the app's card may appear; default home.side
    #[arg(long, value_name = "slot", value_parser = ["home.side", "plans.empty_detail"])]
    pub slot: Vec<String>,
    /// A context slice the app receives
    #[arg(long, value_name = "slice")]
    pub context: Vec<String>,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct ListArgs {
    /// List platform.toml's rows instead, reading nothing from the platform
    #[arg(long)]
    pub pinned: bool,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct UpdateArgs {
    pub id: Option<String>,
    /// The app's manifest file: makes a new version
    #[arg(long, value_name = "path", value_hint = ValueHint::FilePath)]
    pub manifest: Option<PathBuf>,
    /// Cut a draft version from the project's manifest
    #[arg(long, value_name = "semver", conflicts_with = "manifest")]
    pub version: Option<String>,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tunnel {
    Cloudflared,
    None,
}

#[derive(Args, Debug)]
pub struct DevArgs {
    pub name: Option<String>,
    /// The port the app listens on; default the row's
    #[arg(long, value_name = "n", value_parser = clap::value_parser!(u16).range(1..))]
    pub port: Option<u16>,
    /// The tunnel to open; none needs --render-url
    #[arg(long, value_enum)]
    pub tunnel: Option<Tunnel>,
    /// With --tunnel none, the URL the dev version renders from
    #[arg(long = "render-url", value_name = "https-url")]
    pub render_url: Option<String>,
    /// End another terminal's session on this app first
    #[arg(long = "take-over")]
    pub take_over: bool,
    /// Also write the session's variables to this gitignored file, mode 0600
    #[arg(long = "write-env", value_name = "file", value_hint = ValueHint::FilePath)]
    pub write_env: Option<PathBuf>,
    /// List the names of the variables the app receives
    #[arg(long = "show-env")]
    pub show_env: bool,
    /// Return your install to the serving version, and do nothing else
    #[arg(long, conflicts_with_all = ["port", "tunnel", "render_url", "take_over", "write_env", "show_env"])]
    pub reset: bool,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct StatusArgs {
    pub name: Option<String>,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct PinArgs {
    pub app: String,
    /// The project directory; needed for a name
    #[arg(long, value_name = "dir", value_hint = ValueHint::FilePath)]
    pub path: Option<PathBuf>,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct UnpinArgs {
    pub name: String,
    #[command(flatten)]
    pub config: ConfigArg,
}

#[derive(Args, Debug)]
pub struct SubmitArgs {
    pub id: String,
    /// The version to submit
    #[arg(long, value_name = "semver")]
    pub version: String,
}

#[derive(Subcommand, Debug)]
pub enum VersionsCmd {
    /// List an app's versions.
    List(IdArg),
    /// Publish an approved version, or a private app's draft.
    Publish(IdSemver),
    /// Withdraw a version.
    Withdraw(IdSemver),
}

#[derive(Args, Debug)]
pub struct IdSemver {
    pub id: String,
    pub semver: String,
}
