// Package core is the kit's shared core (ADR 16.10.26ad D11, carried to Go
// by 16.10.26af D1): the exit table and the one refusal type, the B2
// classifier, the confirm ritual, the B1 renderer and the signal flag every
// wait polls. The credential store, the canvas planner and the manifest
// rules are its sub-packages.
package core

// Exit is a row of spec/cli.schema.json's exit table (16.10.26ab D3, rows
// 0–8 and 130; 16.10.26ae D10, rows 9–18).
type Exit int

// The exit table, by name.
const (
	ExitOk                 Exit = 0
	ExitFailure            Exit = 1
	ExitUsage              Exit = 2
	ExitAuthRequired       Exit = 3
	ExitNotFound           Exit = 4
	ExitRefused            Exit = 5
	ExitNotConfirmed       Exit = 6
	ExitUnavailable        Exit = 7
	ExitPlatformDisabled   Exit = 8
	ExitToolMissing        Exit = 9
	ExitTunnelFailed       Exit = 10
	ExitDevSessionHeld     Exit = 11
	ExitDevSessionLost     Exit = 12
	ExitAppExited          Exit = 13
	ExitConnectionScripted Exit = 14
	ExitRenderURLEphemeral Exit = 15
	ExitPlatformTOMLSecret Exit = 16
	ExitPlatformTOMLSchema Exit = 17
	ExitAppNotListening    Exit = 18
	ExitInterrupted        Exit = 130
)

var exitNames = map[Exit]string{
	ExitOk: "ok", ExitFailure: "failure", ExitUsage: "usage", ExitAuthRequired: "auth_required",
	ExitNotFound: "not_found", ExitRefused: "refused", ExitNotConfirmed: "not_confirmed",
	ExitUnavailable: "unavailable", ExitPlatformDisabled: "platform_disabled", ExitToolMissing: "tool_missing",
	ExitTunnelFailed: "tunnel_failed", ExitDevSessionHeld: "dev_session_held", ExitDevSessionLost: "dev_session_lost",
	ExitAppExited: "app_exited", ExitConnectionScripted: "connection_scripted",
	ExitRenderURLEphemeral: "render_url_ephemeral", ExitPlatformTOMLSecret: "platform_toml_secret",
	ExitPlatformTOMLSchema: "platform_toml_schema", ExitAppNotListening: "app_not_listening",
	ExitInterrupted: "interrupted",
}

// Name is the row's name, as `apps dev --json` reports how it ended.
func (e Exit) Name() string {
	if name, ok := exitNames[e]; ok {
		return name
	}
	return "failure"
}
