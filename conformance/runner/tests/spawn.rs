//! The runner end to end: `cli-conformance run` spawning `fake-cli`, with
//! the committed cases or fixture ones (ADR 16.10.26ac D5, D9).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde_json::Value;

fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// One run's result lines, by case id.
struct Run {
    lines: BTreeMap<String, Value>,
}

impl Run {
    fn passed(&self, id: &str) -> bool {
        self.lines.get(id).is_some_and(|l| l["result"] == "pass")
    }

    fn mismatches(&self, id: &str) -> String {
        self.lines.get(id).map_or_else(|| format!("{id} did not run"), |l| l["mismatches"].to_string())
    }
}

struct Suite {
    cases: PathBuf,
    schema: PathBuf,
    routes: PathBuf,
    extra: Vec<String>,
}

impl Suite {
    fn committed() -> Suite {
        Suite {
            cases: repo().join("conformance/cases"),
            schema: repo().join("spec/cli.schema.json"),
            routes: repo().join("conformance/routes.toml"),
            extra: Vec::new(),
        }
    }

    /// Runs the suite against the fake with `faults`, narrowed to `only`.
    fn run(&self, faults: &[&str], only: &[&str]) -> Run {
        let dir = tempfile::tempdir().unwrap();
        let (script, out) = (dir.path().join("script.toml"), dir.path().join("results.jsonl"));
        let quoted: Vec<String> = faults.iter().map(|f| format!("{f:?}")).collect();
        std::fs::write(&script, format!("faults = [{}]\n", quoted.join(", "))).unwrap();
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_cli-conformance"));
        cmd.args(["run", "--lang", "rust", "--cases"]).arg(&self.cases).arg("--schema").arg(&self.schema);
        cmd.arg("--routes").arg(&self.routes).args(&self.extra);
        cmd.arg("--").arg(env!("CARGO_BIN_EXE_fake-cli")).arg("--script").arg(&script);
        cmd.env("LINGARA_CONFORMANCE_OUT", &out).env_remove("CI").env_remove("LINGARA_CONFORMANCE_ONLY");
        if !only.is_empty() {
            cmd.env("LINGARA_CONFORMANCE_ONLY", only.join(","));
        }
        let output = cmd.output().unwrap();
        assert_ne!(output.status.code(), Some(2), "the runner refused its input: {}", String::from_utf8_lossy(&output.stderr));
        let text = std::fs::read_to_string(&out).unwrap_or_default();
        let lines = text.lines().map(|l| serde_json::from_str::<Value>(l).unwrap()).map(|v| (v["case"].as_str().unwrap().to_string(), v));
        Run { lines: lines.collect() }
    }
}

/// A fixture suite: `cases` as `(group/slug, yaml)`, a schema naming
/// `probe`, `hang` and `login`, and the committed routes plus `extra_routes`.
fn fixture(dir: &Path, cases: &[(&str, &str)], extra_routes: &str) -> Suite {
    for (name, yaml) in cases {
        let path = dir.join("cases").join(format!("{name}.yaml"));
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, yaml).unwrap();
    }
    let schema = std::fs::read_to_string(repo().join("spec/cli.schema.json")).unwrap().replacen(
        r#""commands": ["#,
        r#""commands": [ { "name": "probe", "summary": "p" }, { "name": "hang", "summary": "h" },"#,
        1,
    );
    std::fs::write(dir.join("cli.schema.json"), schema).unwrap();
    std::fs::copy(repo().join("spec/messages.en.json"), dir.join("messages.en.json")).unwrap();
    let routes = std::fs::read_to_string(repo().join("conformance/routes.toml")).unwrap() + extra_routes;
    std::fs::write(dir.join("routes.toml"), routes).unwrap();
    Suite { cases: dir.join("cases"), schema: dir.join("cli.schema.json"), routes: dir.join("routes.toml"), extra: Vec::new() }
}

const PROBE: &str = "id: b1.probe
title: the prompt is on the terminal and the data on the stdout pipe
behaviours: [B1]
command: probe
argv: [probe]
tty: true
interact:
  - expect: { contains: \"name? \" }
    send: \"Ada\\n\"
expect:
  exit: ok
  stdout: { contains: [\"stdin=true stdout=false stderr=true\", \"hello Ada\"] }
  stderr: { contains: [\"name? \"] }
";

/// 16.10.26ac AC7
#[test]
fn pty_for_stdin_and_stderr_pipe_for_stdout() {
    let dir = tempfile::tempdir().unwrap();
    let prompt_on_stdout = PROBE.replace("b1.probe", "b1.prompt-on-stdout").replace("\"hello Ada\"]", "\"name?\"]");
    let never = PROBE.replace("b1.probe", "b1.never").replace("name? ", "never shown");
    let hang = "id: b1.hang\ntitle: t\nbehaviours: [B1]\ncommand: hang\nargv: [hang]\nexpect: { exit: ok }\n";
    let cases = [("b1/probe", PROBE), ("b1/prompt-on-stdout", prompt_on_stdout.as_str()), ("b1/never", never.as_str()), ("b1/hang", hang)];
    let mut suite = fixture(dir.path(), &cases, "");
    suite.extra = ["--interact-timeout-ms", "800", "--exit-timeout-ms", "2500"].map(String::from).to_vec();
    let run = suite.run(&[], &[]);
    assert!(run.passed("b1.probe"), "{}", run.mismatches("b1.probe"));
    assert!(!run.passed("b1.prompt-on-stdout"), "the prompt never reaches the stdout pipe");
    assert!(run.mismatches("b1.never").contains("not seen within INTERACT_TIMEOUT"), "{}", run.mismatches("b1.never"));
    assert!(run.mismatches("b1.hang").contains("still running after EXIT_TIMEOUT"), "{}", run.mismatches("b1.hang"));
}

const B4_B2: &[&str] = &[
    "b4.login-platform-disabled",
    "b4.route-platform-disabled",
    "b4.not-found-stays-not-found",
    "b4.invalid-grant-stays-auth-required",
    "b2.stale-token-refreshes",
    "b2.second-401-is-auth-required",
    "b2.empty-404-is-failure",
];

/// 16.10.26ac AC9
#[test]
fn platform_disabled_and_refresh_cases_bite() {
    let suite = Suite::committed();
    let run = suite.run(&[], B4_B2);
    for id in B4_B2 {
        assert!(run.passed(id), "{id}: {}", run.mismatches(id));
    }
    let bites = [
        ("raw_404", "b4.route-platform-disabled"),
        ("invalid_grant_is_8", "b4.invalid-grant-stays-auth-required"),
        ("key_on_description", "b4.login-platform-disabled"),
        ("key_on_description", "b4.invalid-grant-stays-auth-required"),
        ("no_refresh", "b2.stale-token-refreshes"),
        ("refresh_twice", "b2.second-401-is-auth-required"),
        ("refresh_on_empty_404", "b2.empty-404-is-failure"),
    ];
    for (fault, id) in bites {
        assert!(!suite.run(&[fault], &[id]).passed(id), "fault {fault} must fail {id}");
    }
}

/// 16.10.26ac AC12
#[test]
fn browser_redirect_gap_and_result_line() {
    let dir = tempfile::tempdir().unwrap();
    let browser = "id: b6.browser-login
title: a browser login follows the authorize redirect to the kit's loopback
behaviours: [B6]
command: login
argv: [login]
credentials: none
interact: [ { browser: follow_url } ]
expect: { exit: ok, stored_credentials: present }
exchanges:
  items:
    - route: oauth.authorize
      request: { query: { response_type: code } }
      response: { redirect: { to_query: redirect_uri, params: { code: c1 }, echo: [state] } }
    - route: oauth.token
      response: { status: 200, json: { access_token: lgr_at_conformance_browser, refresh_token: rt_browser } }
";
    let early = std::fs::read_to_string(repo().join("conformance/cases/b4/login-platform-disabled.yaml"))
        .unwrap()
        .replace("b4.login-platform-disabled", "b4.early")
        .replace("min_gap_ms: 900", "min_gap_ms: 5000");
    let suite = fixture(dir.path(), &[("b6/browser-login", browser), ("b4/early", &early)], "");
    let run = suite.run(&[], &[]);
    assert!(run.passed("b6.browser-login"), "{}", run.mismatches("b6.browser-login"));
    assert!(run.mismatches("b4.early").contains("before min_gap_ms 5000"), "{}", run.mismatches("b4.early"));
    assert_eq!(run.lines["b6.browser-login"]["cli_version"], "0.0.1");
}

/// Every committed case id under `b1`–`b4`, the groups the fake implements.
fn fake_scope() -> Vec<String> {
    let mut ids = Vec::new();
    for group in ["b1", "b2", "b3", "b4"] {
        for entry in std::fs::read_dir(repo().join("conformance/cases").join(group)).unwrap() {
            let name = entry.unwrap().file_name().to_string_lossy().to_string();
            if let Some(slug) = name.strip_suffix(".yaml") {
                ids.push(format!("{group}.{slug}"));
            }
        }
    }
    ids.sort();
    ids
}

/// 16.10.26ac AC13
#[test]
fn every_committed_case_passes_a_conforming_fake() {
    let suite = Suite::committed();
    // The fake keeps the contract for B1–B4 only: the per-command `op`, `b6`
    // and `b7` cases ADR 16.10.26ad adds are the kits' to pass, not its.
    let scope = fake_scope();
    let only: Vec<&str> = scope.iter().map(String::as_str).collect();
    let run = suite.run(&[], &only);
    assert_eq!(run.lines.len(), scope.len(), "every B1–B4 case ran");
    assert!(scope.len() >= 18, "the B1–B4 cases are all still there");
    for (id, line) in &run.lines {
        assert_eq!(line["result"], "pass", "{id}: {}", line["mismatches"]);
    }
    let negatives = [
        ("log_to_stdout", "b1.json-usage-error"),
        ("unknown_exits_4", "b2.unknown-command"),
        ("prompt_in_pipe", "b3.non-tty-refused"),
        ("refuse_pipe_with_flags", "b3.non-tty-flags"),
        ("dark_is_not_found", "b4.route-platform-disabled"),
    ];
    for (fault, id) in negatives {
        assert!(!suite.run(&[fault], &[id]).passed(id), "fault {fault} must fail {id}");
    }
}
