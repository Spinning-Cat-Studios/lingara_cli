package manifest

import (
	"testing"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/jsonv"
)

// 16.10.26ae AC17 (Go): every vector in spec/manifest-warnings.json, read
// from the kit's own copy, gives its expected locales and warnings.
func TestManifestWarningsFollowTheSharedVectors(t *testing.T) {
	file, err := jsonv.Parse(assets.Read("manifest-warnings.json"))
	if err != nil {
		t.Fatal(err)
	}
	vectors := jsonv.Arr(jsonv.Get(file, "vectors"))
	if len(vectors) == 0 {
		t.Fatal("no vectors")
	}
	for _, v := range vectors {
		m := jsonv.Get(v, "manifest")
		if got := Locales(m); !jsonv.Equal(got, jsonv.Get(v, "expect", "locales")) {
			t.Errorf("%s: locales %v", jsonv.Text(v, "name"), got)
		}
		if got := Warnings(m); !jsonv.Equal(got, jsonv.Get(v, "expect", "warnings")) {
			t.Errorf("%s: warnings %v", jsonv.Text(v, "name"), got)
		}
	}
}

func TestQuickTunnelsAndThePlaceholderAreEphemeral(t *testing.T) {
	for _, url := range []string{"https://dev.invalid/", "https://a-b.trycloudflare.com/render", "https://user@X.TryCloudflare.com:443/"} {
		if !Ephemeral(url) {
			t.Errorf("%s is ephemeral", url)
		}
	}
	for _, url := range []string{"https://cards.example.com/render", "https://trycloudflare.com.example/"} {
		if Ephemeral(url) {
			t.Errorf("%s is not ephemeral", url)
		}
	}
}
