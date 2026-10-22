package store

import (
	"os"
	"path/filepath"
	"runtime"

	"github.com/pelletier/go-toml/v2"

	"getlingara.com/cli/internal/core"
)

// ConfigDir is LINGARA_CONFIG_DIR, else $XDG_CONFIG_HOME/lingara, else
// ~/.config/lingara on macOS and Linux alike (never ~/Library/Application
// Support), and %APPDATA%\lingara on Windows (16.10.26ad D4). "" when no
// home can be found.
func ConfigDir(env core.Env) string {
	if dir, ok := env.Get("LINGARA_CONFIG_DIR"); ok {
		return dir
	}
	if runtime.GOOS == "windows" {
		if appdata, ok := env.Get("APPDATA"); ok {
			return filepath.Join(appdata, "lingara")
		}
		return ""
	}
	if xdg, ok := env.Get("XDG_CONFIG_HOME"); ok {
		return filepath.Join(xdg, "lingara")
	}
	home, ok := env.Get("HOME")
	if !ok {
		found, err := os.UserHomeDir()
		if err != nil {
			return ""
		}
		home = found
	}
	return filepath.Join(home, ".config", "lingara")
}

// ProfileConfig is config.toml's [profiles.<name>]: non-secret settings.
type ProfileConfig struct {
	// APIURL is third in D3's API resolution order.
	APIURL string `toml:"api_url"`
	// Output "json" makes --json the profile's default.
	Output string `toml:"output"`
}

// LoadProfileConfig is the profile's settings; a missing or unreadable file
// is no settings.
func LoadProfileConfig(dir, profile string) ProfileConfig {
	if dir == "" {
		return ProfileConfig{}
	}
	data, err := os.ReadFile(filepath.Join(dir, "config.toml"))
	if err != nil {
		return ProfileConfig{}
	}
	var file struct {
		Profiles map[string]ProfileConfig `toml:"profiles"`
	}
	if toml.Unmarshal(data, &file) != nil {
		return ProfileConfig{}
	}
	return file.Profiles[profile]
}
