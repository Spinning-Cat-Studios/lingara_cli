// Package manifest is what the CLI checks in a wire manifest before it
// sends one (ADR 16.10.26ae D6): the ephemeral render URL a version may
// never carry, the manifest rules a warning depends on, and the warnings
// themselves, pinned by spec/manifest-warnings.json (the app kits'
// manifestWarnings). The server stays the authority on every rule; these
// run first so a refusal costs no request.
package manifest

import (
	"sort"
	"strings"

	"getlingara.com/cli/internal/jsonv"
)

// slots are the slots a manifest may name (the app view's AppSlotName).
var slots = []string{"home.side", "plans.empty_detail"}

// warningTexts is the text each warning prints with, as manifest_warning's
// {text}.
var warningTexts = map[string]string{
	"single_locale": "the listing is in its default locale only; give name and description a second locale",
}

// host is the URL's host, lowercased: no scheme, user, port or path.
func host(url string) string {
	rest := url
	if _, after, ok := strings.Cut(url, "://"); ok {
		rest = after
	}
	authority := rest
	if i := strings.IndexAny(rest, "/?#"); i >= 0 {
		authority = rest[:i]
	}
	if i := strings.LastIndex(authority, "@"); i >= 0 {
		authority = authority[i+1:]
	}
	h, _, _ := strings.Cut(authority, ":")
	return strings.ToLower(h)
}

// Ephemeral is a quick tunnel's or the template's placeholder: never a
// version's URL.
func Ephemeral(renderURL string) bool {
	h := host(renderURL)
	return h == "dev.invalid" || h == "trycloudflare.com" || strings.HasSuffix(h, ".trycloudflare.com")
}

func localeMap(v any) bool {
	o := jsonv.AsObject(v)
	if o == nil || o.Len() == 0 {
		return false
	}
	for _, k := range o.Keys() {
		if s, ok := jsonv.Str(o.Get(k)); !ok || s == "" {
			return false
		}
	}
	return true
}

func hasDuplicate(v any) bool {
	items := jsonv.Arr(v)
	for i := range items {
		for j := 0; j < i; j++ {
			if jsonv.Equal(items[i], items[j]) {
				return true
			}
		}
	}
	return false
}

// inMap is whether the member is not an object, or an object with the key.
func inMap(m any, key, locale string) bool {
	o := jsonv.AsObject(jsonv.Get(m, key))
	return o == nil || o.Has(locale)
}

func slotsOK(m any) bool {
	items := jsonv.Arr(jsonv.Get(m, "slots"))
	if len(items) == 0 {
		return false
	}
	for _, item := range items {
		s, isText := jsonv.Str(item)
		if !isText || !contains(slots, s) {
			return false
		}
	}
	return true
}

func contextOK(m any) bool {
	o := jsonv.AsObject(m)
	if !o.Has("context") {
		return true
	}
	if !jsonv.IsArray(o.Get("context")) {
		return false
	}
	for _, item := range jsonv.Arr(o.Get("context")) {
		if _, isText := jsonv.Str(item); !isText {
			return false
		}
	}
	return true
}

func renderURLOK(m any) bool {
	u, ok := jsonv.Str(jsonv.Get(m, "render_url"))
	return ok && len(u) > 8 && strings.EqualFold(u[:8], "https://") && host(u) != ""
}

func contains(list []string, s string) bool {
	for _, item := range list {
		if item == s {
			return true
		}
	}
	return false
}

// BrokenRule is the first rule m breaks, of the ones a warning depends on,
// in the app kits' order; "" when it keeps them all.
func BrokenRule(m any) string {
	locale := jsonv.Text(m, "default_locale")
	version, _ := jsonv.Int(jsonv.Get(m, "manifest_version"))
	checks := []struct {
		rule string
		kept bool
	}{
		{"manifest_version", version == 1},
		{"default_locale", locale != "" && inMap(m, "name", locale) && inMap(m, "description", locale)},
		{"name", localeMap(jsonv.Get(m, "name"))},
		{"description", localeMap(jsonv.Get(m, "description"))},
		{"render_url", renderURLOK(m)},
		{"slots", slotsOK(m)},
		{"context", contextOK(m)},
		{"locales", !jsonv.AsObject(m).Has("locales")},
		{"duplicate", !hasDuplicate(jsonv.Get(m, "slots")) && !hasDuplicate(jsonv.Get(m, "context")) &&
			!hasDuplicate(jsonv.Get(m, "scopes"))},
	}
	for _, c := range checks {
		if !c.kept {
			return c.rule
		}
	}
	return ""
}

func keys(m any, key string) []string {
	return jsonv.AsObject(jsonv.Get(m, key)).Keys()
}

// Locales is where name and description both have an entry, and
// long_description too when present and not null, in byte order; empty for
// a manifest the rules refuse.
func Locales(m any) []string {
	out := []string{}
	if BrokenRule(m) != "" {
		return out
	}
	long := jsonv.Get(m, "long_description")
	for _, l := range keys(m, "name") {
		if contains(keys(m, "description"), l) && (long == nil || contains(keys(m, "long_description"), l)) {
			out = append(out, l)
		}
	}
	sort.Strings(out)
	return out
}

// Warnings is the listing's warnings: v1 single_locale alone. Never a
// refusal.
func Warnings(m any) []string {
	locales := Locales(m)
	if len(locales) == 1 && jsonv.Text(m, "default_locale") == locales[0] {
		return []string{"single_locale"}
	}
	return []string{}
}

// WarningText is the sentence a warning code prints.
func WarningText(code string) string {
	return warningTexts[code]
}
