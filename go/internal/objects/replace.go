package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// webhook outbound replace (16.10.26ad D1, D5): a new endpoint with a new
// signing secret, then the old one deleted. Both prompts, or the --confirm
// check, complete before the new endpoint's first write, so a decline sends
// neither the create nor the delete. If the create lands and the delete is
// refused, the new secret is still revealed and both ids named.

// lookups is the old object and its serving version's body: where the new
// endpoint's client and any omitted flag come from.
func lookups(c *app.Ctx, id string) (any, any, *core.Fail) {
	old, fail := Get(c, WebhookOutbound, id)
	if fail != nil {
		return nil, nil, fail
	}
	semver := baseSemver(old, jsonv.Arr(jsonv.Get(old, "versions")))
	version, fail := VersionOp(c, WebhookOutbound, "version_get", id, semver)
	if fail != nil {
		return nil, nil, fail
	}
	return old, jsonv.Get(version, "body"), nil
}

// Replace runs the replace for id; url nil keeps the old one's, no events
// keep the old ones.
func Replace(c *app.Ctx, id string, url *string, events []string, flags core.ConfirmFlags) *core.Fail {
	var old, body any
	looked := false
	email, fail := core.ConfirmDestructive(c, flags, func() (core.Prompt, *core.Fail) {
		var fail *core.Fail
		if old, body, fail = lookups(c, id); fail != nil {
			return core.Prompt{}, fail
		}
		looked = true
		return NewPrompt(WebhookOutbound, old, id, "", Fixed("consequence_replace")), nil
	})
	if fail != nil {
		return fail
	}
	if !looked {
		if old, body, fail = lookups(c, id); fail != nil {
			return fail
		}
	}
	newURL := jsonv.Text(body, "url")
	if url != nil {
		newURL = *url
	}
	if len(events) == 0 {
		events = jsonv.Strings(jsonv.Get(body, "event_types"))
	}
	made, fail := OutboundCreate(c, jsonv.Text(old, "parent_id"), newURL, events)
	if fail != nil {
		return fail
	}
	if _, fail := Delete(c, WebhookOutbound, id, "", email); fail != nil {
		fail.Made = []core.Made{{Kind: "webhook_outbound", ID: jsonv.Text(made, "id")}}
		return fail.Hint("the old endpoint " + id + " still delivers; delete it with `lingara webhook outbound delete " + id + "`")
	}
	return nil
}
