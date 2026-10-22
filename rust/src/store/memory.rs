//! The in-memory fake the unit tests use (D4), which can also play a
//! keychain that is not there.

use std::cell::RefCell;
use std::collections::BTreeMap;

use super::{CredentialStore, ProfileRecord, StoreError};

#[derive(Default)]
pub struct MemoryStore {
    pub records: RefCell<BTreeMap<String, ProfileRecord>>,
    /// `true` plays a machine with no keychain.
    pub unavailable: bool,
}

impl MemoryStore {
    pub fn missing() -> MemoryStore {
        MemoryStore { unavailable: true, ..MemoryStore::default() }
    }

    fn open(&self) -> Result<(), StoreError> {
        if self.unavailable { Err(StoreError::Unavailable("no keychain here".into())) } else { Ok(()) }
    }
}

impl CredentialStore for MemoryStore {
    fn load(&self, profile: &str) -> Result<Option<ProfileRecord>, StoreError> {
        self.open()?;
        Ok(self.records.borrow().get(profile).cloned())
    }

    fn save(&self, profile: &str, record: &ProfileRecord) -> Result<(), StoreError> {
        self.open()?;
        self.records.borrow_mut().insert(profile.to_string(), record.clone());
        Ok(())
    }

    fn delete(&self, profile: &str) -> Result<(), StoreError> {
        self.open()?;
        self.records.borrow_mut().remove(profile);
        Ok(())
    }
}
