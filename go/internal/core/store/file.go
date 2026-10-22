package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// FileStore is <config dir>/credentials.json, mode 0600 in a 0700
// directory, shaped {"schema": 1, "profiles": {"<profile>": <record>}}
// (16.10.26ad D4).
type FileStore struct {
	Path string
}

// NewFileStore is the store in dir.
func NewFileStore(dir string) *FileStore {
	return &FileStore{Path: filepath.Join(dir, "credentials.json")}
}

type credentialsFile struct {
	Schema   int                      `json:"schema"`
	Profiles map[string]ProfileRecord `json:"profiles"`
}

func (f *FileStore) read() (credentialsFile, error) {
	file := credentialsFile{Schema: 1, Profiles: map[string]ProfileRecord{}}
	data, err := os.ReadFile(f.Path)
	if errors.Is(err, fs.ErrNotExist) {
		return file, nil
	}
	if err != nil {
		return file, fmt.Errorf("%s: %w", f.Path, err)
	}
	if err := json.Unmarshal(data, &file); err != nil {
		return file, fmt.Errorf("%s: %w", f.Path, err)
	}
	if file.Profiles == nil {
		file.Profiles = map[string]ProfileRecord{}
	}
	return file, nil
}

// write is two-space-indented JSON and a newline, the shape the Rust kit's
// serde writes (profiles sorted by name), so a file moves between kits.
func (f *FileStore) write(file credentialsFile) error {
	dir := filepath.Dir(f.Path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("%s: %w", f.Path, err)
	}
	if err := restrict(dir, 0o700); err != nil {
		return fmt.Errorf("%s: %w", f.Path, err)
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(file); err != nil {
		return err
	}
	if err := os.WriteFile(f.Path, buf.Bytes(), 0o600); err != nil {
		return fmt.Errorf("%s: %w", f.Path, err)
	}
	return restrict(f.Path, 0o600)
}

// restrict sets the mode even on a file or directory that already existed;
// Windows has no such modes, and os.Chmod there only toggles read-only.
func restrict(path string, mode fs.FileMode) error {
	return os.Chmod(path, mode)
}

// Has reports whether the file holds the profile.
func (f *FileStore) Has(profile string) bool {
	file, err := f.read()
	if err != nil {
		return false
	}
	_, ok := file.Profiles[profile]
	return ok
}

// Load is the profile's record, nil when the file has none.
func (f *FileStore) Load(profile string) (*ProfileRecord, error) {
	file, err := f.read()
	if err != nil {
		return nil, err
	}
	record, ok := file.Profiles[profile]
	if !ok {
		return nil, nil
	}
	return &record, nil
}

// Save writes the profile's record beside any others.
func (f *FileStore) Save(profile string, record ProfileRecord) error {
	file, err := f.read()
	if err != nil {
		return err
	}
	file.Schema = 1
	file.Profiles[profile] = record
	return f.write(file)
}

// Delete removes the profile; the file goes when it held only that one.
func (f *FileStore) Delete(profile string) error {
	file, err := f.read()
	if err != nil {
		return err
	}
	if _, ok := file.Profiles[profile]; !ok {
		return nil
	}
	delete(file.Profiles, profile)
	if len(file.Profiles) == 0 {
		return os.Remove(f.Path)
	}
	return f.write(file)
}
