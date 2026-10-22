package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// Every object delete (16.10.26ad D5): the ritual over one <word>.get for
// the prompt, then the destructive request with the canonical email.

// Consequence is the kind's consequence_* line, from the object's read.
type Consequence func(object any) string

// DeleteArgs is every delete's id, optional version and ritual flags.
type DeleteArgs struct {
	ID      string
	Version string
	Confirm core.ConfirmFlags
}

// Parent is the read's parent_id, the client's platform object id: the one
// `lingara client delete <id>` takes, never the public client_id.
func Parent(object any) string {
	return jsonv.TextOr(object, "-", "parent_id")
}

// ParentConsequence is a consequence line whose {client} is the parent.
func ParentConsequence(id string) Consequence {
	return func(o any) string { return assets.Message(id, "client", Parent(o)) }
}

// Fixed is a consequence line with no variables.
func Fixed(id string) Consequence {
	return func(any) string { return assets.Message(id) }
}

// NewPrompt is the y/n question's facts for an object.
func NewPrompt(k Kind, object any, id, version string, consequence Consequence) core.Prompt {
	p := core.Prompt{Kind: k.Label, Name: jsonv.TextOr(object, id, "name"), ID: id}
	if version != "" {
		p.Consequence = assets.Message("consequence_version", "semver", version)
	} else {
		p.Consequence = consequence(object)
	}
	p.PinnedBy, _ = jsonv.Uint(jsonv.Get(object, "pinned_by"))
	return p
}

// DeleteObject is one object's delete: the ritual, then the request.
func DeleteObject(c *app.Ctx, k Kind, args DeleteArgs, consequence Consequence) *core.Fail {
	email, fail := core.ConfirmDestructive(c, args.Confirm, func() (core.Prompt, *core.Fail) {
		object, fail := Get(c, k, args.ID)
		if fail != nil {
			return core.Prompt{}, fail
		}
		return NewPrompt(k, object, args.ID, args.Version, consequence), nil
	})
	if fail != nil {
		return fail
	}
	if _, fail := Delete(c, k, args.ID, args.Version, email); fail != nil {
		return fail
	}
	c.Out.Nothing()
	return nil
}
