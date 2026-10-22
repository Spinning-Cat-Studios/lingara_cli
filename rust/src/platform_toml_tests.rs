//! `platform.toml`'s refusals, writing rules and git checks (ADR 16.10.26ae
//! D4, AC6 and AC23), against files in a temp directory.

use std::path::Path;
use std::process::Command;

use super::write::{self, NewRow};
use super::{git, load};
use crate::exit::Exit;

fn file(dir: &Path, text: &str) -> std::path::PathBuf {
    let path = dir.join("platform.toml");
    std::fs::write(&path, text).unwrap();
    path
}

fn git_in(dir: &Path, args: &[&str]) {
    let ok = Command::new("git").arg("-C").arg(dir).args(args).output().map(|o| o.status.success()).unwrap_or(false);
    assert!(ok, "git {args:?}");
}

/// 16.10.26ae AC6: a secret-shaped value or key is refused naming the key,
/// never the value; a client id and `TOKENIZER` pass; an unknown schema is
/// `platform_toml_schema`.
#[test]
fn secret_values_and_keys_are_refused_by_name() {
    let dir = tempfile::tempdir().unwrap();
    let refused = [
        ("[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\nHOOK = \"lgr_whsec_abcdefgh12345\"\n", "apps.env.HOOK"),
        ("[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\nclient_secret = \"x\"\n", "apps.env.client_secret"),
        ("[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\nAPI_TOKEN = \"x\"\n", "apps.env.API_TOKEN"),
        ("[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\ndb_password = \"x\"\n", "apps.env.db_password"),
    ];
    for (body, key) in refused {
        let path = file(dir.path(), &format!("schema = 1\n{body}"));
        let fail = load(&path, "platform.toml").expect_err(key);
        assert_eq!(fail.exit, Exit::PlatformTomlSecret, "{key}");
        assert!(fail.message.contains(key) && !fail.message.contains("lgr_whsec_abcdefgh12345"), "{}", fail.message);
    }
    let accepted = "schema = 1\n[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\nLINGARA_CLIENT_ID = \"lgr_cid_abcdefgh12345\"\nTOKENIZER = \"bpe\"\n";
    assert!(load(&file(dir.path(), accepted), "platform.toml").is_ok());
    let schema = load(&file(dir.path(), "schema = 2\n"), "platform.toml").expect_err("schema 2");
    assert_eq!(schema.exit, Exit::PlatformTomlSchema);
    let stray = load(&file(dir.path(), "schema = 1\n[[apps]]\nname = \"a\"\npath = \".\"\nlisted = true\n"), "platform.toml").expect_err("stray");
    assert_eq!(stray.exit, Exit::PlatformTomlSchema);
}

/// The rows create, pin, registration and unpin write, byte for byte.
#[test]
fn rows_are_written_as_the_spec_spells_them() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("platform.toml");
    let row = NewRow { name: "demo", path: ".", port: 8787, start: Some("npm start"), manifest: Some(("manifest", "lingara/connection.js")) };
    write::pin_row(&path, &row, None).unwrap();
    write::set_id(&path, "demo", "7d1e2f30-0000-4000-8000-000000000001").unwrap();
    let text = std::fs::read_to_string(&path).unwrap();
    let want = format!(
        "{}\n[[apps]]\nname = \"demo\"\nid = \"7d1e2f30-0000-4000-8000-000000000001\"\npath = \".\"\nport = 8787\nstart = \"npm start\"\nmanifest = \"lingara/connection.js\"\n",
        write::FILE_HEADER
    );
    assert_eq!(text, want);
    let example = std::fs::read_to_string(dir.path().join("platform.toml.example")).unwrap();
    assert!(example.starts_with(write::EXAMPLE_HEADER) && example.contains("id = \"00000000-0000-0000-0000-000000000000\""));
    assert!(load(&path, "platform.toml").unwrap().apps[0].id.is_some());
    assert!(write::remove_row(&path, "demo").unwrap());
    assert_eq!(std::fs::read_to_string(&path).unwrap(), write::FILE_HEADER);
    write::ensure_gitignore(dir.path()).unwrap();
    write::ensure_gitignore(dir.path()).unwrap();
    assert_eq!(std::fs::read_to_string(dir.path().join(".gitignore")).unwrap(), "platform.toml\n");
}

/// 16.10.26ae AC23: in a git repository a tracked platform.toml is seen and
/// an unignored path is not ignored; outside one both checks are skipped.
#[test]
fn git_checks_warn_refuse_and_skip_outside_a_repo() {
    let outside = tempfile::tempdir().unwrap();
    let loose = file(outside.path(), "schema = 1\n");
    assert!(!git::tracked(&loose), "no repository: nothing is tracked");
    assert!(git::ignored(&outside.path().join(".env.local")), "no repository: the check is skipped");

    let repo = tempfile::tempdir().unwrap();
    git_in(repo.path(), &["init", "-q"]);
    let tracked = file(repo.path(), "schema = 1\n");
    git_in(repo.path(), &["add", "platform.toml"]);
    assert!(git::tracked(&tracked), "a tracked platform.toml warns");
    assert!(!git::ignored(&repo.path().join(".env.local")), "an unignored env_file is refused");
    std::fs::write(repo.path().join(".gitignore"), ".env.local\n").unwrap();
    assert!(git::ignored(&repo.path().join(".env.local")));
}
