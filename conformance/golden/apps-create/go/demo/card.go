// Package app is Demo: one render and one action, keyed on the learner.
package app

import (
	"strconv"

	lingaraapps "github.com/Spinning-Cat-Studios/lingara_app_framework_sdk/go"
)

// One catalogue, checked at startup: a key missing in a locale falls back to
// the default's text. Add a locale by adding its strings.
var messages = mustMessages(lingaraapps.NewMessages("en", lingaraapps.Catalogue{
	"en":      {"heading": "Today's five", "review": "Review 3 words", "done": "Done ({streak})"},
	"zh-Hans": {"heading": "今日五词", "review": "复习 3 个词", "done": "完成（{streak}）"},
}))

func mustMessages(m *lingaraapps.Messages, err error) *lingaraapps.Messages {
	if err != nil {
		panic(err) // a *lingaraapps.MessagesError names the broken rule
	}
	return m
}

// The learner's UI language is the request's Locale, never the languages slice.
func localisedCard(locale string, streak int) (lingaraapps.Card, error) {
	t := messages.For(locale)
	return lingaraapps.NewCard().
		Heading(t.T("heading"), 1).
		Term(lingaraapps.Term{Word: "雨", Reading: "yǔ", Gloss: "rain", Lang: "zh"}).
		List(lingaraapps.Item.Text(t.T("review")), lingaraapps.Item.Term(lingaraapps.Term{Word: "二", Reading: "èr"})).
		Button(t.TWith("done", map[string]string{"streak": strconv.Itoa(streak)}), "done").
		Build()
}
