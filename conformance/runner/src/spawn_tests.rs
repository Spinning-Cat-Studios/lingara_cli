//! D5's environment and the `ONLY` guard, as functions.

use std::collections::BTreeMap;
use std::path::Path;

use crate::case;
use crate::spawn::{build_env, check_pass_env, only_filter};

fn parent() -> BTreeMap<String, String> {
    [
        ("PATH", "/usr/bin"),
        ("JAVA_HOME", "/opt/jdk"),
        ("CI", "true"),
        ("LINGARA_TOKEN", "lgr_at_leak"),
        ("HOME", "/Users/real"),
        ("LC_ALL", "fr_FR.UTF-8"),
        ("SECRET_THING", "x"),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect()
}

/// 16.10.26ac AC8
#[test]
fn environment_is_built_from_scratch() {
    let case = case::parse(
        "id: b2.t\ntitle: t\nbehaviours: [B2]\ncommand: apps list\nargv: [apps, list]\ncredentials: ci\n\
         env: { LINGARA_PROFILE: work }\nexpect: { exit: ok }\n",
    )
    .unwrap();
    let env = build_env(&parent(), &["JAVA_HOME".to_string()], Path::new("/tmp/case"), "http://127.0.0.1:9", &case);
    let mut names: Vec<&str> = env.keys().map(String::as_str).collect();
    names.sort_unstable();
    assert_eq!(
        names,
        [
            "HOME", "JAVA_HOME", "LANG", "LINGARA_API_URL", "LINGARA_CONFIG_DIR", "LINGARA_KEYRING", "LINGARA_NO_BROWSER",
            "LINGARA_PLATFORM_CLIENT_ID", "LINGARA_PLATFORM_CLIENT_SECRET", "LINGARA_PLATFORM_URL", "LINGARA_PROFILE",
            "NO_COLOR", "PATH", "TERM",
        ]
    );
    assert_eq!(env["HOME"], "/tmp/case");
    assert_eq!(env["LANG"], "en_US.UTF-8");
    assert_eq!(env["LINGARA_KEYRING"], "file");
    assert!(!env.contains_key("CI") && !env.contains_key("LINGARA_TOKEN") && !env.contains_key("LC_ALL"));

    for refused in ["CI", "LINGARA_API_URL", "HOME", "LC_ALL", "LANG", "NO_COLOR", "TERM"] {
        assert!(check_pass_env(&[refused.to_string()]).is_err(), "--pass-env {refused} must be refused");
    }
    assert!(check_pass_env(&["JAVA_HOME".to_string(), "GEM_HOME".to_string()]).is_ok());
}

/// 16.10.26ac AC10
#[test]
fn no_narrowed_run_in_ci() {
    assert!(only_filter(Some("b1.json-usage-error"), Some("true")).is_err());
    assert_eq!(only_filter(Some("a, b"), None).unwrap(), Some(vec!["a".to_string(), "b".to_string()]));
    assert_eq!(only_filter(None, Some("true")).unwrap(), None, "an unnarrowed CI run is fine");
}
