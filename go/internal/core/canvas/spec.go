package canvas

import "getlingara.com/cli/internal/jsonv"

// A field node's op body: the web's specFromFields then specBody
// (testRun.ts, plan.ts), over the node's saved fields. A create's client is
// always billing_mode: allowance; no body carries enabled, api_version or
// client_type. The app manifest is lingara.v1's manifest() wire form, with
// the defaults it fills. Each function answers the body, or why the fields
// are not its kind's spec.

func text(v any, what string) (string, string) {
	s, ok := jsonv.Str(v)
	if !ok {
		return "", what + " must be a string"
	}
	return s, ""
}

func texts(v any, what string) ([]string, string) {
	items, ok := v.([]any)
	if !ok {
		return nil, what + " must be an array of strings"
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		s, bad := text(item, what)
		if bad != "" {
			return nil, bad
		}
		out = append(out, s)
	}
	return out, ""
}

// optionalTexts is texts, or an empty list for null or absent.
func optionalTexts(v any, what string) ([]string, bool, string) {
	if v == nil {
		return []string{}, false, ""
	}
	items, bad := texts(v, what)
	return items, true, bad
}

// localeText is {locale: s} for a plain string; a map must hold strings only.
func localeText(v any, locale, what string) (any, string) {
	switch t := v.(type) {
	case string:
		return jsonv.Obj(locale, t), ""
	case *jsonv.Object:
		for _, k := range t.Keys() {
			if _, ok := jsonv.Str(t.Get(k)); !ok {
				return nil, what + " must be a string or an object of strings"
			}
		}
		return t, ""
	}
	return nil, what + " must be a string or an object of strings"
}

// clientSpec is {name, scopes, redirect_uris?} plus billing_mode on a create.
func clientSpec(fields any, create bool) (any, string) {
	name, bad := text(jsonv.Get(fields, "name"), "name")
	if bad != "" {
		return nil, bad
	}
	scopes, bad := texts(jsonv.Get(fields, "scopes"), "scopes")
	if bad != "" {
		return nil, bad
	}
	body := jsonv.Obj("name", name, "scopes", scopes)
	uris, present, bad := optionalTexts(jsonv.Get(fields, "redirectUris"), "redirectUris")
	if bad != "" {
		return nil, bad
	}
	if present {
		body.Set("redirect_uris", uris)
	}
	if create {
		body.Set("billing_mode", "allowance")
	}
	return body, ""
}

func webhookSpec(fields any) (any, string) {
	url, bad := text(jsonv.Get(fields, "url"), "url")
	if bad != "" {
		return nil, bad
	}
	events, bad := texts(jsonv.Get(fields, "events"), "events")
	if bad != "" {
		return nil, bad
	}
	return jsonv.Obj("url", url, "event_types", events), ""
}

func appSpec(fields any) (any, string) {
	m, bad := wireManifest(jsonv.Get(fields, "manifest"))
	if bad != "" {
		return nil, bad
	}
	return jsonv.Obj("manifest", m), ""
}

// AppName is the name an app op registers under: the manifest's in its
// default locale, else any of its names, else fallback.
func AppName(body any, fallback string) string {
	m := jsonv.Get(body, "manifest")
	locale := jsonv.TextOr(m, "en", "default_locale")
	names := jsonv.Get(m, "name")
	if s, ok := jsonv.Str(jsonv.Get(names, locale)); ok {
		return s
	}
	o := jsonv.AsObject(names)
	for _, k := range o.Keys() {
		if s, ok := jsonv.Str(o.Get(k)); ok {
			return s
		}
	}
	return fallback
}
