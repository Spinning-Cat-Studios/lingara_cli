//! Token storage (ADR 16.10.26ad D4): one `CredentialStore` trait with a
//! keychain adapter, a file adapter and an in-memory fake, and the one rule
//! that picks between them.
//!
//! `LINGARA_KEYRING=file` uses the file and says nothing; `=keychain` uses
//! the keychain and makes an unavailable one `auth_required`; unset tries the
//! keychain and falls back to the file, warning once for each profile it
//! first writes there. Secrets never go in `config.toml`.

pub mod config;
pub mod file;
pub mod keychain;
#[cfg(test)]
pub mod memory;

use serde::{Deserialize, Serialize};

use crate::exit::Fail;
use file::FileStore;

/// One profile's credentials: D4's record, stored whole.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ProfileRecord {
    pub developer_id: String,
    pub email: String,
    /// The token response's space-separated `scope`, rewritten at each refresh.
    pub scopes: String,
    pub refresh_token: String,
    pub access_token: String,
    /// RFC 3339, UTC.
    pub access_expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreError {
    /// No keychain on this machine (a headless Linux box, say).
    Unavailable(String),
    Broken(String),
}

pub trait CredentialStore {
    fn load(&self, profile: &str) -> Result<Option<ProfileRecord>, StoreError>;
    fn save(&self, profile: &str, record: &ProfileRecord) -> Result<(), StoreError>;
    fn delete(&self, profile: &str) -> Result<(), StoreError>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Auto,
    File,
    Keychain,
}

impl Mode {
    pub fn from_env(value: Option<&str>) -> Mode {
        match value {
            Some("file") => Mode::File,
            Some("keychain") => Mode::Keychain,
            _ => Mode::Auto,
        }
    }
}

/// The store a run uses, chosen by `Mode`.
pub struct Stores {
    pub mode: Mode,
    pub keychain: Box<dyn CredentialStore>,
    pub file: FileStore,
}

fn broken(e: StoreError) -> Fail {
    match e {
        StoreError::Unavailable(why) => Fail::auth(format!("the keychain is unavailable: {why}")),
        StoreError::Broken(why) => Fail::failure(format!("the credential store could not be read: {why}")),
    }
}

impl Stores {
    pub fn load(&self, profile: &str) -> Result<Option<ProfileRecord>, Fail> {
        match self.mode {
            Mode::File => self.file.load(profile).map_err(broken),
            Mode::Keychain => self.keychain.load(profile).map_err(broken),
            Mode::Auto => match self.keychain.load(profile) {
                Ok(Some(record)) => Ok(Some(record)),
                Ok(None) | Err(StoreError::Unavailable(_)) => self.file.load(profile).map_err(broken),
                Err(e) => Err(broken(e)),
            },
        }
    }

    /// Saves `record`; the warning to print, if this write fell back to the
    /// file silently for the first time.
    pub fn save(&self, profile: &str, record: &ProfileRecord) -> Result<Option<String>, Fail> {
        match self.mode {
            Mode::File => self.file.save(profile, record).map(|()| None).map_err(broken),
            Mode::Keychain => self.keychain.save(profile, record).map(|()| None).map_err(broken),
            Mode::Auto => match self.keychain.save(profile, record) {
                Ok(()) => Ok(None),
                Err(StoreError::Unavailable(_)) => self.fall_back(profile, record),
                Err(e) => Err(broken(e)),
            },
        }
    }

    fn fall_back(&self, profile: &str, record: &ProfileRecord) -> Result<Option<String>, Fail> {
        let first = !self.file.has(profile);
        self.file.save(profile, record).map_err(broken)?;
        let path = self.file.path.display();
        Ok(first.then(|| format!("storing credentials in {path}; set LINGARA_KEYRING=keychain to require the keychain")))
    }

    /// Forgets `profile` everywhere it might be.
    pub fn delete(&self, profile: &str) -> Result<(), Fail> {
        if self.mode != Mode::File {
            match self.keychain.delete(profile) {
                Ok(()) | Err(StoreError::Unavailable(_)) => {}
                Err(e) if self.mode == Mode::Keychain => return Err(broken(e)),
                Err(_) => {}
            }
        }
        if self.mode == Mode::Keychain { Ok(()) } else { self.file.delete(profile).map_err(broken) }
    }
}

#[cfg(test)]
#[path = "store_tests.rs"]
mod store_tests;
