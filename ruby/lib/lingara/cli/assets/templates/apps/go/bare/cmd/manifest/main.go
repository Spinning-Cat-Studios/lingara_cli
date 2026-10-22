// Command manifest prints {{display_name}}'s manifest. `lingara apps update`
// runs `go run ./cmd/manifest` and uploads what this prints, so print nothing else.
package main

import (
	"log"
	"os"

	lingaraapps "github.com/Spinning-Cat-Studios/lingara_app_framework_sdk/go"
)

func main() {
	var slots []lingaraapps.AppSlotName
	for _, s := range {{slots}} {
		slots = append(slots, lingaraapps.AppSlotName(s))
	}
	var context []lingaraapps.ContextSliceKind
	for _, c := range {{context}} {
		context = append(context, lingaraapps.ContextSliceKind(c))
	}
	manifest, err := lingaraapps.NewManifest().
		DefaultLocale("en").
		Names(map[string]string{"en": "{{display_name}}", "zh-Hans": "{{display_name}}"}).
		Descriptions(map[string]string{"en": "{{description}}", "zh-Hans": "{{description}}"}).
		// `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
		RenderURL("https://dev.invalid/").
		Slots(slots...).
		Context(context...).
		Build()
	if err != nil {
		log.Fatal(err) // a *lingaraapps.ManifestError names the broken rule
	}
	data, err := manifest.ToJSON()
	if err != nil {
		log.Fatal(err)
	}
	os.Stdout.Write(append(data, '\n'))
}
