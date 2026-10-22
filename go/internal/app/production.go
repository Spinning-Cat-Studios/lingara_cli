package app

import (
	"os/exec"
	"runtime"
	"strings"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
)

// UserAgent is `lingara-cli/<semver> (go; <os>)`, contract B5.
func UserAgent(version, kit string) string {
	return "lingara-cli/" + version + " (" + kit + "; " + runtime.GOOS + ")"
}

// AcceptLanguage is LC_ALL, else LC_MESSAGES, else LANG, as BCP 47:
// en_US.UTF-8 is en-US; C and POSIX name no language.
func AcceptLanguage(env core.Env) string {
	raw := ""
	for _, name := range []string{"LC_ALL", "LC_MESSAGES", "LANG"} {
		if v, ok := env.Get(name); ok {
			raw = v
			break
		}
	}
	tag := raw
	if i := strings.IndexAny(raw, ".@"); i >= 0 {
		tag = raw[:i]
	}
	tag = strings.ReplaceAll(tag, "_", "-")
	if tag == "C" || tag == "POSIX" {
		return ""
	}
	return tag
}

// openBrowser hands url to the platform's opener; false when it could not.
func openBrowser(url string) bool {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	return cmd.Start() == nil
}

// Production is the App main runs: net/http, the system clock, the stores
// LINGARA_KEYRING picks, the real terminal and browser.
func Production(env core.Env, version, kit string) *App {
	endpoints := assets.LoadEndpoints()
	configDir := store.ConfigDir(env)
	fileDir := configDir
	if fileDir == "" {
		fileDir = "."
	}
	mode, _ := env.Get("LINGARA_KEYRING")
	portal, ok := env.Get("LINGARA_PLATFORM_URL")
	if !ok {
		portal = endpoints.PlatformURL
	}
	noBrowser := env["LINGARA_NO_BROWSER"] == "1"
	return &App{
		HTTP: &httpx.HTTP{
			Transport: httpx.NewNetHTTP(), Clock: httpx.SystemClock{}, API: endpoints.APIURL, Portal: portal,
			UserAgent: UserAgent(version, kit), AcceptLanguage: AcceptLanguage(env),
		},
		Env:       env,
		Stores:    &store.Stores{Mode: store.ModeFromEnv(mode), Keychain: store.Keychain{}, File: store.NewFileStore(fileDir)},
		Terminal:  &Stdio{},
		Browser:   func(url string) bool { return !noBrowser && openBrowser(url) },
		Endpoints: endpoints,
		ConfigDir: configDir,
	}
}
