//! `<config dir>/credentials.json`, mode `0600` in a `0700` directory,
//! shaped `{"schema": 1, "profiles": {"<profile>": <record>}}` (D4).

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use super::{CredentialStore, ProfileRecord, StoreError};

#[derive(Serialize, Deserialize, Default)]
struct CredentialsFile {
    schema: u32,
    #[serde(default)]
    profiles: BTreeMap<String, ProfileRecord>,
}

pub struct FileStore {
    pub path: PathBuf,
}

impl FileStore {
    pub fn new(dir: PathBuf) -> FileStore {
        FileStore { path: dir.join("credentials.json") }
    }

    fn read(&self) -> Result<CredentialsFile, StoreError> {
        match std::fs::read(&self.path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(|e| StoreError::Broken(format!("{}: {e}", self.path.display()))),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(CredentialsFile { schema: 1, ..Default::default() }),
            Err(e) => Err(StoreError::Broken(format!("{}: {e}", self.path.display()))),
        }
    }

    fn write(&self, file: &CredentialsFile) -> Result<(), StoreError> {
        let io = |e: std::io::Error| StoreError::Broken(format!("{}: {e}", self.path.display()));
        if let Some(dir) = self.path.parent() {
            std::fs::create_dir_all(dir).map_err(io)?;
            restrict(dir, 0o700).map_err(io)?;
        }
        let text = serde_json::to_string_pretty(file).map_err(|e| StoreError::Broken(e.to_string()))?;
        std::fs::write(&self.path, text + "\n").map_err(io)?;
        restrict(&self.path, 0o600).map_err(io)
    }

    pub fn has(&self, profile: &str) -> bool {
        self.read().is_ok_and(|f| f.profiles.contains_key(profile))
    }
}

#[cfg(unix)]
fn restrict(path: &std::path::Path, mode: u32) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
}

#[cfg(not(unix))]
fn restrict(_path: &std::path::Path, _mode: u32) -> std::io::Result<()> {
    Ok(())
}

impl CredentialStore for FileStore {
    fn load(&self, profile: &str) -> Result<Option<ProfileRecord>, StoreError> {
        Ok(self.read()?.profiles.remove(profile))
    }

    fn save(&self, profile: &str, record: &ProfileRecord) -> Result<(), StoreError> {
        let mut file = self.read()?;
        file.schema = 1;
        file.profiles.insert(profile.to_string(), record.clone());
        self.write(&file)
    }

    /// Removes the profile; the file goes when it held only that one.
    fn delete(&self, profile: &str) -> Result<(), StoreError> {
        let mut file = self.read()?;
        if file.profiles.remove(profile).is_none() {
            return Ok(());
        }
        if file.profiles.is_empty() {
            return std::fs::remove_file(&self.path).map_err(|e| StoreError::Broken(e.to_string()));
        }
        self.write(&file)
    }
}
