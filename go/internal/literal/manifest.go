package literal

import "getlingara.com/cli/internal/jsonv"

// The closed key map from lingara.v1's ManifestOptions to the wire
// manifest (spec/connection-literal.md), in lingara.manifest's member
// order. A key the map does not name is refused, never passed through.

var manifestKeys = [][2]string{
	{"defaultLocale", "default_locale"},
	{"name", "name"},
	{"description", "description"},
	{"renderUrl", "render_url"},
	{"slots", "slots"},
	{"context", "context"},
	{"scopes", "scopes"},
	{"tutorNote", "tutor_note"},
	{"longDescription", "long_description"},
	{"developer", "developer"},
	{"images", "images"},
}

var (
	required      = []string{"name", "description", "renderUrl", "slots"}
	developerKeys = [][2]string{{"name", "name"}, {"url", "url"}, {"supportUrl", "support_url"}}
	imageKeys     = [][2]string{{"sha256", "sha256"}, {"role", "role"}, {"alt", "alt"}}
)

func find(members []Member, key string) (Lit, bool) {
	for _, m := range members {
		if m.Key == key {
			return m.Value, true
		}
	}
	return Lit{}, false
}

func known(table [][2]string, key string) bool {
	for _, pair := range table {
		if pair[0] == key {
			return true
		}
	}
	return false
}

// localeMap is a string as the default locale's; an object passes its tags
// through.
func localeMap(l Lit, locale string) any {
	if l.Kind == "str" {
		return jsonv.Obj(locale, l.Text)
	}
	return l.JSON()
}

// renamed is an object whose keys must all be in table, renamed through it.
func renamed(l Lit, table [][2]string, locale string) (any, *Refusal) {
	if l.Kind != "obj" {
		return l.JSON(), nil
	}
	for _, m := range l.Members {
		if !known(table, m.Key) {
			return nil, refuse("manifest_key", m.At)
		}
	}
	out := jsonv.NewObject()
	for _, pair := range table {
		if v, ok := find(l.Members, pair[0]); ok {
			if pair[0] == "alt" {
				out.Set(pair[1], localeMap(v, locale))
			} else {
				out.Set(pair[1], v.JSON())
			}
		}
	}
	return out, nil
}

func images(l Lit, locale string) (any, *Refusal) {
	if l.Kind != "arr" {
		return l.JSON(), nil
	}
	out := make([]any, 0, len(l.Items))
	for _, item := range l.Items {
		v, refusal := renamed(item, imageKeys, locale)
		if refusal != nil {
			return nil, refusal
		}
		out = append(out, v)
	}
	return out, nil
}

// convert is the wire manifest for the manifest object at at.
func convert(members []Member, at Pos) (any, *Refusal) {
	if refusal := checkKeys(members, at); refusal != nil {
		return nil, refusal
	}
	locale := "en"
	defaultLocale, hasLocale := find(members, "defaultLocale")
	if hasLocale && defaultLocale.Kind == "str" {
		locale = defaultLocale.Text
	}
	out := jsonv.Obj("manifest_version", 1, "default_locale", "en")
	if hasLocale {
		out.Set("default_locale", defaultLocale.JSON())
	}
	for _, pair := range manifestKeys[1:] {
		v, present := find(members, pair[0])
		value, keep, refusal := member(pair[0], v, present, locale)
		if refusal != nil {
			return nil, refusal
		}
		if keep {
			out.Set(pair[1], value)
		}
	}
	return out, nil
}

// checkKeys refuses a key the map does not name, or a required one missing.
func checkKeys(members []Member, at Pos) *Refusal {
	for _, m := range members {
		if !known(manifestKeys, m.Key) {
			return refuse("manifest_key", m.At)
		}
	}
	for _, k := range required {
		if _, ok := find(members, k); !ok {
			return refuse("manifest_key", at)
		}
	}
	return nil
}

func member(key string, v Lit, present bool, locale string) (any, bool, *Refusal) {
	if !present {
		switch key {
		case "context", "scopes":
			return []any{}, true, nil
		case "tutorNote":
			return false, true, nil
		}
		return nil, false, nil
	}
	switch key {
	case "name", "description", "longDescription":
		return localeMap(v, locale), true, nil
	case "developer":
		d, refusal := renamed(v, developerKeys, locale)
		return d, true, refusal
	case "images":
		i, refusal := images(v, locale)
		return i, true, refusal
	}
	return v.JSON(), true, nil
}
