package canvas

import "getlingara.com/cli/internal/jsonv"

// wireManifest is lingara.v1's manifest(), in the order it writes the
// members.
func wireManifest(o any) (any, string) {
	if !jsonv.IsObject(o) {
		return nil, "manifest must be an object"
	}
	locale := "en"
	if v := jsonv.Get(o, "defaultLocale"); v != nil {
		s, bad := text(v, "defaultLocale")
		if bad != "" {
			return nil, bad
		}
		locale = s
	}
	m := jsonv.Obj("manifest_version", 1, "default_locale", locale)
	steps := []func() string{
		func() string { return setLocaleText(m, o, "name", "name", locale) },
		func() string { return setLocaleText(m, o, "description", "description", locale) },
		func() string { return setText(m, o, "renderUrl", "render_url") },
		func() string { return setTexts(m, o, "slots", "slots", false) },
		func() string { return setTexts(m, o, "context", "context", true) },
		func() string { return setTexts(m, o, "scopes", "scopes", true) },
		func() string {
			tutor, _ := jsonv.Bool(jsonv.Get(o, "tutorNote"))
			m.Set("tutor_note", tutor)
			return ""
		},
		func() string { return listing(o, locale, m) },
	}
	for _, step := range steps {
		if bad := step(); bad != "" {
			return nil, bad
		}
	}
	return m, ""
}

func setLocaleText(m *jsonv.Object, o any, from, to, locale string) string {
	v, bad := localeText(jsonv.Get(o, from), locale, from)
	if bad == "" {
		m.Set(to, v)
	}
	return bad
}

func setText(m *jsonv.Object, o any, from, to string) string {
	v, bad := text(jsonv.Get(o, from), from)
	if bad == "" {
		m.Set(to, v)
	}
	return bad
}

func setTexts(m *jsonv.Object, o any, from, to string, optional bool) string {
	var items []string
	bad := ""
	if optional {
		items, _, bad = optionalTexts(jsonv.Get(o, from), from)
	} else {
		items, bad = texts(jsonv.Get(o, from), from)
	}
	if bad == "" {
		m.Set(to, items)
	}
	return bad
}

// listing is the listing's three members, each written only when its
// option is set.
func listing(o any, locale string, m *jsonv.Object) string {
	if jsonv.Get(o, "longDescription") != nil {
		if bad := setLocaleText(m, o, "longDescription", "long_description", locale); bad != "" {
			return bad
		}
	}
	if dev := jsonv.AsObject(jsonv.Get(o, "developer")); dev != nil {
		d, bad := developer(dev)
		if bad != "" {
			return bad
		}
		m.Set("developer", d)
	}
	if images, ok := jsonv.Get(o, "images").([]any); ok {
		wire, bad := imagesWire(images, locale)
		if bad != "" {
			return bad
		}
		m.Set("images", wire)
	}
	return ""
}

func developer(dev *jsonv.Object) (any, string) {
	name, bad := text(dev.Get("name"), "developer.name")
	if bad != "" {
		return nil, bad
	}
	d := jsonv.Obj("name", name)
	for _, pair := range [][2]string{{"url", "url"}, {"supportUrl", "support_url"}} {
		if !dev.Has(pair[0]) {
			continue
		}
		v, bad := text(dev.Get(pair[0]), pair[0])
		if bad != "" {
			return nil, bad
		}
		d.Set(pair[1], v)
	}
	return d, ""
}

func imagesWire(images []any, locale string) (any, string) {
	out := make([]any, 0, len(images))
	for _, i := range images {
		sha, bad := text(jsonv.Get(i, "sha256"), "images[].sha256")
		if bad != "" {
			return nil, bad
		}
		role, bad := text(jsonv.Get(i, "role"), "images[].role")
		if bad != "" {
			return nil, bad
		}
		alt, bad := localeText(jsonv.Get(i, "alt"), locale, "images[].alt")
		if bad != "" {
			return nil, bad
		}
		out = append(out, jsonv.Obj("sha256", sha, "role", role, "alt", alt))
	}
	return out, ""
}
