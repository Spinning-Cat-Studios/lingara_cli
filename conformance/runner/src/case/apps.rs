//! The keys ADR 16.10.26ae D11 adds to the case schema, valid in the `apps`
//! group only (`check` refuses them elsewhere): a stateful dev-session mock
//! and its seed, a project fixture, a fake tunnel, a signal, a longer exit
//! timeout, and three judges over what the run left behind.

use serde::Deserialize;
use serde_json::Value;

/// `mock`: which stateful model serves its route group.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MockKind {
    /// The `app_dev.*` routes, from `mock/platform_dev.rs`.
    PlatformDev,
}

/// One scripted renew answer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RenewAnswer {
    Ok,
    NotLive,
    Expired,
}

/// `mock_seed`: the model's starting state.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MockSeed {
    /// What an open answers as `lease_secs`; default 300.
    pub lease_secs: Option<u64>,
    /// A live lease another session holds until then: the first open
    /// without `take_over` answers `409 dev_session_held`.
    pub held_until: Option<String>,
    /// Successive renew answers; once spent, every renew is `ok`.
    #[serde(default)]
    pub renew: Vec<RenewAnswer>,
    /// The first n `app_dev.patch` calls answer `409 version_conflict`.
    #[serde(default)]
    pub version_conflicts: u32,
    /// The dev row's starting `render_url` (absent: the serving version).
    pub render_url: Option<String>,
}

/// The fake app's schedule.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AppSchedule {
    pub exit_after_ms: Option<u64>,
    #[serde(default)]
    pub status: i32,
    /// `false`: never accept a connection (app_not_listening).
    pub listen: Option<bool>,
}

/// `project`: a fixture slug, or one with the fake app's schedule.
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum Project {
    Slug(String),
    Scheduled {
        slug: String,
        #[serde(default)]
        app: AppSchedule,
    },
}

impl Project {
    pub fn slug(&self) -> &str {
        match self {
            Project::Slug(s) | Project::Scheduled { slug: s, .. } => s,
        }
    }

    pub fn app(&self) -> AppSchedule {
        match self {
            Project::Slug(_) => AppSchedule::default(),
            Project::Scheduled { app, .. } => app.clone(),
        }
    }
}

/// The fake cloudflared's schedule.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TunnelSchedule {
    /// Incarnation k dies after `die_after_ms[k-1]`; past the list, it lives.
    #[serde(default)]
    pub die_after_ms: Vec<u64>,
    pub ready_after_ms: Option<u64>,
    /// Its first n probes answer `530`.
    #[serde(default)]
    pub answer_530_first: u32,
}

/// `tunnel`: `fake`, `missing`, or `{fake: <schedule>}`.
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum TunnelSpec {
    Word(TunnelWord),
    Scheduled { fake: TunnelSchedule },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TunnelWord {
    Fake,
    Missing,
}

impl TunnelSpec {
    /// The fake's schedule, or `None` for `missing`.
    pub fn schedule(&self) -> Option<TunnelSchedule> {
        match self {
            TunnelSpec::Word(TunnelWord::Fake) => Some(TunnelSchedule::default()),
            TunnelSpec::Word(TunnelWord::Missing) => None,
            TunnelSpec::Scheduled { fake } => Some(fake.clone()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub enum SignalName {
    #[serde(rename = "SIGINT")]
    Sigint,
    #[serde(rename = "SIGTERM")]
    Sigterm,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SignalAfter {
    pub message: String,
    pub count: Option<usize>,
}

/// `signal`: sent to the CLI once stderr has shown `after.message`.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Signal {
    pub after: SignalAfter,
    pub send: SignalName,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CountRange {
    pub min: usize,
    pub max: Option<usize>,
}

/// One `expect.calls` entry: a `platform_dev` route, its JSON body, and how
/// many consecutive calls it may match.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CallMatch {
    pub route: String,
    pub json: Option<Value>,
    pub count: Option<CountRange>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChildEnv {
    #[serde(default)]
    pub present: Vec<String>,
    #[serde(default)]
    pub absent_prefix: Vec<String>,
    #[serde(default)]
    pub equals_session: Vec<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Files {
    pub golden: Option<String>,
    #[serde(default)]
    pub absent_text: Vec<String>,
    #[serde(default)]
    pub present: Vec<String>,
}

/// `exit_timeout_secs`' range (D11).
pub const EXIT_TIMEOUT_RANGE: std::ops::RangeInclusive<u64> = 30..=90;
