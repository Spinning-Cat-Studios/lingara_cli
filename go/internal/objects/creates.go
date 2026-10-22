package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// A create that may take several calls (16.10.26ad D1, D2). It ends with
// one <word>.get, printed with any secret it minted. Failing part-way, it
// still reveals that secret on stdout and names every object it made on
// stderr, then exits with the failing call's code. Nothing is rolled back.

const secretOnce = "The secret is shown once: store it now. It cannot be read again."

// Creation is what a create has made so far.
type Creation struct {
	Made []core.Made
	// Secret is {id, value}, the D6 member a create's JSON gains.
	Secret *jsonv.Object
}

// Object creates one object, names it as made, then publishes it when
// private.
func (cr *Creation) Object(c *app.Ctx, k Kind, name, parent string, body any) (any, *core.Fail) {
	created, fail := Create(c, k, name, parent, body)
	if fail != nil {
		return nil, fail
	}
	cr.Made = append(cr.Made, core.Made{Kind: k.Word, ID: jsonv.Text(created, "id")})
	if _, fail := PublishIfPrivate(c, k, created, jsonv.Text(created, "version", "semver")); fail != nil {
		return nil, fail
	}
	return created, nil
}

// MintSecret is the one-time secret. 409 object_projection_pending means
// the client is still publishing (16.10.26v §5): refused, with a retry hint.
func (cr *Creation) MintSecret(c *app.Ctx, k Kind, id string) *core.Fail {
	minted, fail := SecretCreate(c, k, id)
	if fail != nil {
		if fail.Code == "object_projection_pending" {
			fail.Hint("the client is still publishing; run `lingara client secret create " + id + "` in a moment")
		}
		return fail
	}
	cr.Secret = jsonv.Obj("id", jsonv.Get(minted, "secret_id"), "value", jsonv.Get(minted, "secret"))
	return nil
}

// Conclude is the final read and its printing, or the failure with what
// was made. fail is the steps' failure, if any; otherwise k and id name the
// object to read.
func (cr *Creation) Conclude(c *app.Ctx, k Kind, id string, fail *core.Fail) (any, *core.Fail) {
	var object any
	if fail == nil {
		object, fail = Get(c, k, id)
	}
	if fail != nil {
		cr.print(c, nil)
		fail.Made = cr.Made
		return nil, fail
	}
	cr.print(c, object)
	return object, nil
}

func (cr *Creation) print(c *app.Ctx, object any) {
	if object == nil && cr.Secret == nil {
		return
	}
	if c.Out.JSON {
		value, ok := jsonv.Clone(object).(*jsonv.Object)
		if !ok {
			value = jsonv.NewObject()
		}
		if cr.Secret != nil {
			value.Set("secret", cr.Secret)
		}
		c.Out.JSONValue(value)
	} else {
		var rows [][]string
		if object != nil {
			rows = core.Fields(object)
		}
		if cr.Secret != nil {
			rows = append(rows, []string{"SECRET", core.Cell(cr.Secret.Get("value"))})
		}
		c.Out.Print(core.Render([]string{"FIELD", "VALUE"}, rows))
	}
	if cr.Secret != nil {
		c.Out.Note(secretOnce)
	}
}
