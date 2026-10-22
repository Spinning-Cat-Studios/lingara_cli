//! `run` (ADR 16.10.26ac D5): every case, one fresh mock, temp dir and
//! child each, judged and appended as one JSONL line.

use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use serde_json::json;

use crate::case::schema::{self, Schema};
use crate::case::{self, Case, Loaded};
use crate::invariants::{self, Evidence, USER_AGENT};
use crate::judge;
use crate::mock::{Logged, Mock, substitutions};
use crate::routes::{self, Routes};
use crate::spawn::{self, BROWSER_AGENT, Job, Limits, SignalPlan, Step};

pub struct RunArgs {
    pub lang: String,
    pub cases: PathBuf,
    pub schema: PathBuf,
    pub routes: PathBuf,
    pub pass_env: Vec<String>,
    pub command: Vec<String>,
    pub limits: Limits,
}

struct Ctx {
    args: RunArgs,
    schema: Schema,
    routes: Routes,
    fixture: PathBuf,
    /// Absolute: what `{fixtures}` in a case's argv becomes.
    fixtures: PathBuf,
    /// `conformance/golden/`, which `expect.files.golden` names under.
    goldens: PathBuf,
    /// The fake cloudflared's `--version`: `spec/tools.toml`'s floor.
    min_version: String,
    parent_env: BTreeMap<String, String>,
    rt: tokio::runtime::Runtime,
}

/// `Ok(true)` when every case passed; `Err` for input that runs nothing.
pub fn run(args: RunArgs) -> Result<bool, String> {
    spawn::check_pass_env(&args.pass_env)?;
    let schema = schema::load(&args.schema)?;
    let routes = routes::load(&args.routes)?;
    let cases = case::load_dir(&args.cases).map_err(|e| e.join("\n"))?;
    let refused: Vec<String> = cases.iter().flat_map(|l| case::check::check(l, &schema, &routes)).collect();
    if !refused.is_empty() {
        return Err(refused.join("\n"));
    }
    let only = spawn::only_filter(std::env::var("LINGARA_CONFORMANCE_ONLY").ok().as_deref(), std::env::var("CI").ok().as_deref())?;
    let out_path = std::env::var_os("LINGARA_CONFORMANCE_OUT").map(PathBuf::from);
    let rt = tokio::runtime::Runtime::new().map_err(|e| e.to_string())?;
    let fixtures = args.cases.parent().unwrap_or(Path::new(".")).join("fixtures");
    let fixtures = fixtures.canonicalize().unwrap_or(fixtures);
    let fixture = fixtures.join("credentials/stored.json");
    let goldens = fixtures.with_file_name("golden");
    let min_version = spawn::apps::min_version(&args.schema);
    let ctx = Ctx { args, schema, routes, fixture, fixtures, goldens, min_version, parent_env: std::env::vars().collect(), rt };
    let selected: Vec<&Loaded> =
        cases.iter().filter(|l| only.as_ref().is_none_or(|ids| ids.contains(&l.case.id))).collect();
    let mut passed = 0;
    for loaded in &selected {
        let line = run_case(&ctx, &loaded.case);
        if line["result"] != "pass" {
            println!("  {}", loaded.case.title);
        }
        passed += usize::from(line["result"] == "pass");
        report(&line);
        if let Some(path) = &out_path {
            append(path, &line)?;
        }
    }
    println!("{passed}/{} cases pass for {}", selected.len(), ctx.args.lang);
    Ok(passed == selected.len())
}

fn report(line: &serde_json::Value) {
    let mark = if line["result"] == "pass" { "✓" } else { "✗" };
    println!("{mark} {}", line["case"].as_str().unwrap_or(""));
    for m in line["mismatches"].as_array().into_iter().flatten() {
        println!("    {}", m.as_str().unwrap_or(""));
    }
}

fn append(path: &Path, line: &serde_json::Value) -> Result<(), String> {
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    writeln!(f, "{line}").map_err(|e| format!("{}: {e}", path.display()))
}

fn run_case(ctx: &Ctx, case: &Case) -> serde_json::Value {
    let started = Instant::now();
    let (mismatches, log) = match judge_case(ctx, case) {
        Ok(found) => found,
        Err(e) => (vec![e], Vec::new()),
    };
    json!({
        "case": case.id,
        "lang": ctx.args.lang,
        "cli_version": cli_version(&log),
        "result": if mismatches.is_empty() { "pass" } else { "fail" },
        "mismatches": mismatches,
        "duration_ms": started.elapsed().as_millis() as u64,
    })
}

/// The `<semver>` of the kit's first request's `User-Agent`, if it sent one
/// (the runner's own browser GET is not the kit's).
fn cli_version(log: &[Logged]) -> Option<String> {
    let first = log.iter().find(|l| l.request.header("user-agent") != Some(BROWSER_AGENT))?;
    let ua = first.request.header("user-agent")?;
    USER_AGENT.captures(ua).and_then(|c| c.get(1)).map(|m| m.as_str().to_string())
}

fn judge_case(ctx: &Ctx, case: &Case) -> Result<(Vec<String>, Vec<Logged>), String> {
    let mock = ctx.rt.block_on(Mock::start(case, &ctx.routes)).map_err(|e| format!("mock: {e}"))?;
    let home = TempDir::new().map_err(|e| format!("temp dir: {e}"))?;
    let mut env = spawn::build_env(&ctx.parent_env, &ctx.args.pass_env, &home.0, &mock.url, case);
    spawn::seed(case, &home.0, &ctx.fixture).map_err(|e| format!("credentials seed: {e}"))?;
    // ADR 16.10.26ae D11: an `apps` case's project, fakes and timeout.
    let setup = spawn::apps::setup(case, &home.0, &ctx.fixtures, &ctx.min_version)?;
    env.extend(setup.env);
    let steps = steps_of(case, &ctx.schema);
    // A file a verb reads (`--manifest`, `--file`) is a committed fixture,
    // named `{fixtures}/…` so the path is absolute from any temp dir.
    let fixtures = ctx.fixtures.display().to_string();
    let argv: Vec<String> = case.argv.iter().map(|a| a.replace("{fixtures}", &fixtures)).collect();
    let mut limits = ctx.args.limits;
    limits.exit = case.exit_timeout_secs.map_or(limits.exit, Duration::from_secs);
    let job = Job {
        command: &ctx.args.command,
        argv: &argv,
        env: &env,
        cwd: &setup.cwd,
        tty: case.tty,
        stdin: case.stdin.as_deref(),
        steps: &steps,
        limits,
        signal: signal_of(case, &ctx.schema),
    };
    let outcome = spawn::run(&job);
    let subs = substitutions(&mock.url, &ctx.routes);
    let finished = mock.finish();
    let mut mismatches = outcome.failures.clone();
    mismatches.extend(spawn::apps::orphans(&home.0));
    mismatches.extend(judge::judge(case, &outcome, &ctx.schema, &subs, &home.0));
    let apps = judge::AppsEvidence { dev_calls: &finished.dev_calls, minted: &finished.minted, subs: &subs, home: &home.0, goldens: &ctx.goldens };
    mismatches.extend(judge::judge_apps(case, &apps));
    let evidence = Evidence { case, schema: &ctx.schema, routes: &ctx.routes, outcome: &outcome, log: &finished.log };
    mismatches.extend(invariants::check(&evidence));
    mismatches.extend(finished.unconsumed.iter().map(|u| format!("exchanges: {u}")));
    Ok((mismatches, finished.log))
}

/// `signal`: the message's fragments, how many times, and the signal.
fn signal_of(case: &Case, schema: &Schema) -> Option<SignalPlan> {
    let s = case.signal.as_ref()?;
    let template = schema.messages.get(&s.after.message).map_or("", String::as_str);
    let signal = match s.send {
        case::apps::SignalName::Sigint => nix::libc::SIGINT,
        case::apps::SignalName::Sigterm => nix::libc::SIGTERM,
    };
    Some(SignalPlan { fragments: judge::fragments_of(template, &BTreeMap::new()), count: s.after.count.unwrap_or(1), signal })
}

fn steps_of(case: &Case, schema: &Schema) -> Vec<Step> {
    let step = |i: &case::Interact| match (&i.browser, &i.expect) {
        (Some(_), _) => Step::Browser,
        (None, expect) => Step::Wait {
            label: expect.as_ref().map_or_else(String::new, |m| format!("{:?}", m.message.as_ref().or(m.contains.as_ref()))),
            fragments: expect.as_ref().map(|m| judge::text_fragments(m, schema)).unwrap_or_default(),
            send: i.send.clone(),
        },
    };
    case.interact.iter().map(step).collect()
}

/// A fresh directory per case, removed when the case is judged.
struct TempDir(PathBuf);

impl TempDir {
    fn new() -> std::io::Result<TempDir> {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let n = NEXT.fetch_add(1, Ordering::SeqCst);
        let dir = std::env::temp_dir().join(format!("lingara-conformance-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir)?;
        Ok(TempDir(dir.canonicalize()?))
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
