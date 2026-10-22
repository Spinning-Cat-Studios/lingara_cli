// Package store is token storage (ADR 16.10.26ad D4, kept by 16.10.26af
// D1): one CredentialStore interface with a keychain adapter, a file
// adapter and an in-memory fake, and the one rule that picks between them.
//
// LINGARA_KEYRING=file uses the file and says nothing; =keychain uses the
// keychain and makes an unavailable one auth_required; unset tries the
// keychain and falls back to the file, warning once for each profile it
// first writes there. Secrets never go in config.toml.
package store

import (
	"errors"

	"getlingara.com/cli/internal/core"
)

// ProfileRecord is one profile's credentials: D4's record, stored whole, in
// the member order every kit writes.
type ProfileRecord struct {
	DeveloperID string `json:"developer_id"`
	Email       string `json:"email"`
	// Scopes is the token response's space-separated scope, rewritten at
	// each refresh.
	Scopes       string `json:"scopes"`
	RefreshToken string `json:"refresh_token"`
	AccessToken  string `json:"access_token"`
	// AccessExpiresAt is RFC 3339, UTC.
	AccessExpiresAt string `json:"access_expires_at"`
}

// ErrUnavailable is a store with no keychain behind it (a headless Linux
// box, say); any other error is a broken store.
var ErrUnavailable = errors.New("the keychain is unavailable")

// CredentialStore is one place a profile record can live.
type CredentialStore interface {
	// Load is the record, or nil when there is none.
	Load(profile string) (*ProfileRecord, error)
	Save(profile string, record ProfileRecord) error
	Delete(profile string) error
}

// Mode is LINGARA_KEYRING's choice.
type Mode int

// The three modes.
const (
	ModeAuto Mode = iota
	ModeFile
	ModeKeychain
)

// ModeFromEnv reads LINGARA_KEYRING; anything else is auto.
func ModeFromEnv(value string) Mode {
	switch value {
	case "file":
		return ModeFile
	case "keychain":
		return ModeKeychain
	}
	return ModeAuto
}

// Stores is the store a run uses, chosen by Mode.
type Stores struct {
	Mode     Mode
	Keychain CredentialStore
	File     *FileStore
}

func broken(err error) *core.Fail {
	if errors.Is(err, ErrUnavailable) {
		return core.Auth(err.Error())
	}
	return core.Failure("the credential store could not be read: " + err.Error())
}

// Load is the profile's record from the store the mode picks, nil when none.
func (s *Stores) Load(profile string) (*ProfileRecord, *core.Fail) {
	var record *ProfileRecord
	var err error
	switch s.Mode {
	case ModeFile:
		record, err = s.File.Load(profile)
	case ModeKeychain:
		record, err = s.Keychain.Load(profile)
	default:
		record, err = s.Keychain.Load(profile)
		if record == nil && (err == nil || errors.Is(err, ErrUnavailable)) {
			record, err = s.File.Load(profile)
		}
	}
	if err != nil {
		return nil, broken(err)
	}
	return record, nil
}

// Save writes the record; the warning to print, if this write fell back to
// the file silently for the first time.
func (s *Stores) Save(profile string, record ProfileRecord) (string, *core.Fail) {
	var err error
	switch s.Mode {
	case ModeFile:
		err = s.File.Save(profile, record)
	case ModeKeychain:
		err = s.Keychain.Save(profile, record)
	default:
		err = s.Keychain.Save(profile, record)
		if errors.Is(err, ErrUnavailable) {
			return s.fallBack(profile, record)
		}
	}
	if err != nil {
		return "", broken(err)
	}
	return "", nil
}

func (s *Stores) fallBack(profile string, record ProfileRecord) (string, *core.Fail) {
	first := !s.File.Has(profile)
	if err := s.File.Save(profile, record); err != nil {
		return "", broken(err)
	}
	if !first {
		return "", nil
	}
	return "storing credentials in " + s.File.Path + "; set LINGARA_KEYRING=keychain to require the keychain", nil
}

// Delete forgets the profile everywhere it might be.
func (s *Stores) Delete(profile string) *core.Fail {
	if s.Mode != ModeFile {
		err := s.Keychain.Delete(profile)
		if err != nil && !errors.Is(err, ErrUnavailable) && s.Mode == ModeKeychain {
			return broken(err)
		}
	}
	if s.Mode == ModeKeychain {
		return nil
	}
	if err := s.File.Delete(profile); err != nil {
		return broken(err)
	}
	return nil
}
