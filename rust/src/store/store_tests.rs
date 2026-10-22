use std::path::Path;

use super::memory::MemoryStore;
use super::*;

fn record(email: &str) -> ProfileRecord {
    ProfileRecord { email: email.into(), access_token: "lgr_at_x".into(), refresh_token: "lgr_rt_x".into(), ..Default::default() }
}

fn stores(mode: Mode, keychain: MemoryStore, dir: &Path) -> Stores {
    Stores { mode, keychain: Box::new(keychain), file: FileStore::new(dir.join("lingara")) }
}

#[cfg(unix)]
fn mode_of(path: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

/// 16.10.26ad AC6 keychain first, file fallback, never config
#[test]
fn keychain_first_file_fallback_never_config() {
    let dir = tempfile::tempdir().unwrap();
    // A keychain that is there: used, and the file is never written.
    let present = stores(Mode::Auto, MemoryStore::default(), dir.path());
    assert_eq!(present.save("default", &record("a@b.c")).unwrap(), None);
    assert_eq!(present.load("default").unwrap(), Some(record("a@b.c")));
    assert!(!present.file.path.exists());

    // No keychain: the file, with exactly one warning for the profile.
    let fallback = stores(Mode::Auto, MemoryStore::missing(), dir.path());
    let warning = fallback.save("default", &record("a@b.c")).unwrap();
    assert!(warning.is_some_and(|w| w.contains("LINGARA_KEYRING=keychain")));
    assert_eq!(fallback.save("default", &record("a@b.c")).unwrap(), None, "warned once");
    assert_eq!(fallback.load("default").unwrap(), Some(record("a@b.c")));
    #[cfg(unix)]
    assert_eq!((mode_of(&fallback.file.path), mode_of(fallback.file.path.parent().unwrap())), (0o600, 0o700));
    let text = std::fs::read_to_string(&fallback.file.path).unwrap();
    let shape: serde_json::Value = serde_json::from_str(&text).unwrap();
    assert_eq!((shape["schema"].as_u64(), shape["profiles"]["default"]["email"].as_str()), (Some(1), Some("a@b.c")));

    // LINGARA_KEYRING=file: the file, silently.
    let other = tempfile::tempdir().unwrap();
    assert_eq!(stores(Mode::File, MemoryStore::default(), other.path()).save("p", &record("x@y.z")).unwrap(), None);

    // LINGARA_KEYRING=keychain with no keychain: auth_required, no fallback.
    let strict = stores(Mode::Keychain, MemoryStore::missing(), other.path());
    let fail = strict.save("p", &record("x@y.z")).unwrap_err();
    assert_eq!(fail.exit, crate::exit::Exit::AuthRequired);
    assert!(strict.load("p").is_err());

    // Nothing secret ever reaches config.toml: no store writes it.
    assert!(!dir.path().join("lingara/config.toml").exists() && !other.path().join("lingara/config.toml").exists());
}

#[test]
fn the_committed_fixture_reads_back_as_the_default_profile() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("../conformance/fixtures/credentials/stored.json");
    std::fs::copy(fixture, dir.path().join("credentials.json")).unwrap();
    let file = FileStore::new(dir.path().to_path_buf());
    let stored = file.load("default").unwrap().unwrap();
    assert_eq!((stored.email.as_str(), stored.access_token.as_str()), ("dev@conformance.invalid", "lgr_at_conformance_stored"));
    assert_eq!(stored.access_expires_at, "2099-01-01T00:00:00Z");
    assert_eq!(stored.scopes, "platform:read platform:write canvas:read canvas:write");
}

#[test]
fn delete_forgets_the_profile_and_the_empty_file() {
    let dir = tempfile::tempdir().unwrap();
    let s = stores(Mode::File, MemoryStore::default(), dir.path());
    s.save("a", &record("a@b.c")).unwrap();
    s.save("b", &record("b@b.c")).unwrap();
    s.delete("a").unwrap();
    assert_eq!((s.load("a").unwrap(), s.load("b").unwrap().is_some()), (None, true));
    s.delete("b").unwrap();
    assert!(!s.file.path.exists());
}
