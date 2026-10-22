package store

import (
	"encoding/json"
	"errors"
	"fmt"

	"github.com/zalando/go-keyring"
)

// service is the keychain service every kit stores under: account the
// profile name, value the profile record as one JSON document (D4).
const service = "lingara-cli"

// Keychain is the OS keychain through zalando/go-keyring (ADR 16.10.26af
// D2): macOS Keychain, the Windows Credential Manager and the Secret
// Service, all without cgo.
type Keychain struct{}

// unavailable maps go-keyring's failures: no entry is no record; anything
// else is a platform store that is missing or locked, which auto mode
// answers with the file.
func unavailable(err error) error {
	return fmt.Errorf("%w: %v", ErrUnavailable, err)
}

// Load is the profile's record, nil when there is none.
func (Keychain) Load(profile string) (*ProfileRecord, error) {
	text, err := keyring.Get(service, profile)
	if errors.Is(err, keyring.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, unavailable(err)
	}
	var record ProfileRecord
	if err := json.Unmarshal([]byte(text), &record); err != nil {
		return nil, fmt.Errorf("the keychain's %s entry: %w", profile, err)
	}
	return &record, nil
}

// Save writes the record as one JSON document.
func (Keychain) Save(profile string, record ProfileRecord) error {
	text, err := json.Marshal(record)
	if err != nil {
		return err
	}
	if err := keyring.Set(service, profile, string(text)); err != nil {
		return unavailable(err)
	}
	return nil
}

// Delete forgets the profile; no entry is not an error.
func (Keychain) Delete(profile string) error {
	err := keyring.Delete(service, profile)
	if err == nil || errors.Is(err, keyring.ErrNotFound) {
		return nil
	}
	return unavailable(err)
}

// Memory is the in-memory fake the unit tests use (D4), which can also play
// a keychain that is not there.
type Memory struct {
	Records map[string]ProfileRecord
	// Missing plays a machine with no keychain.
	Missing bool
}

func (m *Memory) open() error {
	if m.Missing {
		return unavailable(errors.New("no keychain here"))
	}
	if m.Records == nil {
		m.Records = map[string]ProfileRecord{}
	}
	return nil
}

// Load is the profile's record, nil when there is none.
func (m *Memory) Load(profile string) (*ProfileRecord, error) {
	if err := m.open(); err != nil {
		return nil, err
	}
	record, ok := m.Records[profile]
	if !ok {
		return nil, nil
	}
	return &record, nil
}

// Save keeps the record.
func (m *Memory) Save(profile string, record ProfileRecord) error {
	if err := m.open(); err != nil {
		return err
	}
	m.Records[profile] = record
	return nil
}

// Delete forgets the profile.
func (m *Memory) Delete(profile string) error {
	if err := m.open(); err != nil {
		return err
	}
	delete(m.Records, profile)
	return nil
}
