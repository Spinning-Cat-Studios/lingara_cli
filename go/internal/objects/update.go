package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// Overlay writes a command's flags onto a whole version body.
type Overlay func(body *jsonv.Object)

// BodyUpdate is D2's body update: overlay the flags on the one editable
// version (version_put at its draft_version), else on the serving (or
// highest) version as a new one (version_create). Always a whole body.
func BodyUpdate(c *app.Ctx, k Kind, id string, overlay Overlay) (any, *core.Fail) {
	current, fail := Get(c, k, id)
	if fail != nil {
		return nil, fail
	}
	versions := jsonv.Arr(jsonv.Get(current, "versions"))
	var answer any
	if semver, ok := editable(versions); ok {
		answer, fail = putEditable(c, k, id, semver, overlay)
	} else {
		answer, fail = createFrom(c, k, id, baseSemver(current, versions), overlay)
	}
	if fail != nil {
		return nil, fail
	}
	published, fail := PublishIfPrivate(c, k, current, jsonv.Text(answer, "semver"))
	if fail != nil {
		return nil, fail
	}
	if published != nil {
		return published, nil
	}
	return answer, nil
}

// editable is the semver of the one draft or changes_requested version.
func editable(versions []any) (string, bool) {
	for _, v := range versions {
		state := jsonv.Text(v, "state")
		if state == "draft" || state == "changes_requested" {
			return jsonv.Str(jsonv.Get(v, "semver"))
		}
	}
	return "", false
}

// baseSemver is the serving version, else the first listed; "" for none.
func baseSemver(current any, versions []any) string {
	if s, ok := jsonv.Str(jsonv.Get(current, "serving_version")); ok {
		return s
	}
	if len(versions) > 0 {
		return jsonv.Text(versions[0], "semver")
	}
	return ""
}

func putEditable(c *app.Ctx, k Kind, id, semver string, overlay Overlay) (any, *core.Fail) {
	version, fail := VersionOp(c, k, "version_get", id, semver)
	if fail != nil {
		return nil, fail
	}
	body := overlaid(jsonv.Get(version, "body"), overlay)
	edit := jsonv.Obj("body", body, "draft_version", jsonv.Get(version, "draft_version"))
	return send(c, httpx.Object(k.Word, "version_put"), []httpx.Pair{{"id", id}, {"semver", semver}}, edit)
}

func createFrom(c *app.Ctx, k Kind, id, semver string, overlay Overlay) (any, *core.Fail) {
	var start any = jsonv.NewObject()
	if semver != "" {
		version, fail := VersionOp(c, k, "version_get", id, semver)
		if fail != nil {
			return nil, fail
		}
		start = jsonv.Get(version, "body")
	}
	made := jsonv.Obj("body", overlaid(start, overlay))
	return send(c, httpx.Object(k.Word, "version_create"), idVar(id), made)
}

func overlaid(body any, overlay Overlay) *jsonv.Object {
	o, ok := jsonv.Clone(body).(*jsonv.Object)
	if !ok {
		o = jsonv.NewObject()
	}
	overlay(o)
	return o
}
