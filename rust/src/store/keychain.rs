//! The OS keychain (D4): service `lingara-cli`, account the profile name,
//! value the profile record as one JSON document. `keyring` v3 speaks to
//! macOS Keychain, Windows Credential Manager and the Secret Service.

use super::{CredentialStore, ProfileRecord, StoreError};

const SERVICE: &str = "lingara-cli";

pub struct Keychain;

fn entry(profile: &str) -> Result<keyring::Entry, StoreError> {
    keyring::Entry::new(SERVICE, profile).map_err(mapped)
}

/// No entry is `None`; a missing or locked platform store is unavailable.
fn mapped(e: keyring::Error) -> StoreError {
    match e {
        keyring::Error::PlatformFailure(_) | keyring::Error::NoStorageAccess(_) => StoreError::Unavailable(e.to_string()),
        other => StoreError::Broken(other.to_string()),
    }
}

impl CredentialStore for Keychain {
    fn load(&self, profile: &str) -> Result<Option<ProfileRecord>, StoreError> {
        match entry(profile)?.get_password() {
            Ok(text) => serde_json::from_str(&text).map(Some).map_err(|e| StoreError::Broken(e.to_string())),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(mapped(e)),
        }
    }

    fn save(&self, profile: &str, record: &ProfileRecord) -> Result<(), StoreError> {
        let text = serde_json::to_string(record).map_err(|e| StoreError::Broken(e.to_string()))?;
        entry(profile)?.set_password(&text).map_err(mapped)
    }

    fn delete(&self, profile: &str) -> Result<(), StoreError> {
        match entry(profile)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(mapped(e)),
        }
    }
}
