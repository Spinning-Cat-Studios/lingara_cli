package apps

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/manifest"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/literal"
	"getlingara.com/cli/internal/objects"
	"getlingara.com/cli/internal/platform"
	"getlingara.com/cli/internal/supervise"
)

// A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6
// steps 1, 4 and 5): read from lingara/connection.js by the literal reader,
// or from manifest_command's stdout; checked; its warnings printed; and,
// for a row with no id, registered as a private app.

const connectionFile = "lingara/connection.js"

func manifestFile(t *platform.Toml, row platform.Row) string {
	name := row.Manifest
	if name == "" {
		name = connectionFile
	}
	return filepath.Join(t.ProjectDir(row), filepath.FromSlash(name))
}

// Source is the manifest's source as messages name it, relative to cwd.
func Source(t *platform.Toml, row platform.Row, cwd string) string {
	if row.ManifestCommand != "" {
		return row.ManifestCommand
	}
	return platform.Relative(manifestFile(t, row), cwd)
}

// ReadManifest is the wire manifest the project describes, checked before
// any request.
func ReadManifest(t *platform.Toml, row platform.Row, cwd string) (any, *core.Fail) {
	shown := Source(t, row, cwd)
	var m any
	if row.ManifestCommand != "" {
		var fail *core.Fail
		if m, fail = runCommand(row.ManifestCommand, t.ProjectDir(row)); fail != nil {
			return nil, fail
		}
	} else {
		text, err := os.ReadFile(manifestFile(t, row))
		if err != nil {
			return nil, core.Usage(shown + ": " + err.Error())
		}
		var refusal *literal.Refusal
		if m, refusal = literal.AppManifest(string(text)); refusal != nil {
			return nil, refusal.Fail(shown)
		}
	}
	if rule := manifest.BrokenRule(m); rule != "" {
		return nil, core.NewFail(core.ExitUsage, "manifest_invalid", shown+": the manifest breaks the "+rule+" rule")
	}
	return m, nil
}

// runCommand is D3: manifest_command through the platform shell, in the
// project; its stdout is the wire manifest, its stderr the developer's to
// see.
func runCommand(command, dir string) (any, *core.Fail) {
	shell := supervise.Shell(command)
	shell.Dir, shell.Stderr = dir, os.Stderr
	out, err := shell.Output()
	if err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return nil, core.Failure(command + " exited " + supervise.Status(exit.ProcessState) + "; fix it, then run lingara apps update")
		}
		return nil, core.Failure(command + ": " + err.Error())
	}
	m, err := jsonv.Parse(out)
	if err != nil {
		return nil, core.Usage(command + " printed no manifest JSON: " + err.Error())
	}
	return m, nil
}

// Warn is D6 step 5: one manifest_warning line each, never a failure.
func Warn(c *app.Ctx, m any) []string {
	codes := manifest.Warnings(m)
	for _, code := range codes {
		c.Out.Note(assets.Message("manifest_warning", "code", code, "text", manifest.WarningText(code)))
	}
	return codes
}

// DisplayName is the display name D2 derives: each - a space, each word
// capitalised.
func DisplayName(name string) string {
	words := strings.Split(name, "-")
	for i, w := range words {
		if w != "" && w[0] >= 'a' && w[0] <= 'z' {
			words[i] = string(w[0]-('a'-'A')) + w[1:]
		}
	}
	return strings.Join(words, " ")
}

// RegisterRow is D1 step 2 / D6 step 4: the private OAuth client, then the
// app under it through the registration form's own steps, the id written
// into the row. Prints the app as the registration form does.
func RegisterRow(c *app.Ctx, t *platform.Toml, row platform.Row, m any) (any, *core.Fail) {
	title := DisplayName(row.Name)
	clientBody := jsonv.Obj("client_type", "confidential", "allowed_scopes", []string{}, "billing_mode", "allowance", "redirect_uris", []string{})
	made := &objects.Creation{}
	appID := ""
	client, fail := made.Object(c, objects.OAuthClient, title, "", clientBody)
	if fail == nil {
		var created any
		created, fail = made.Object(c, objects.App, title, jsonv.Text(client, "id"), jsonv.Obj("manifest", m))
		appID = jsonv.Text(created, "id")
	}
	registered, fail := made.Conclude(c, objects.App, appID, fail)
	if fail != nil {
		return nil, fail
	}
	if fail := platform.SetID(t.File, row.Name, jsonv.Text(registered, "id")); fail != nil {
		return nil, fail
	}
	return registered, nil
}
