package app

import (
	"context"
	"net/http"
	"sync"

	lingaraapps "github.com/Spinning-Cat-Studios/lingara_app_framework_sdk/go"
)

// Handler is the app behind the kit's net/http handler, which reads the raw
// body, verifies, dispatches and replies. secrets is LINGARA_APP_SECRET: two
// during a rotation.
func Handler(secrets ...string) (http.Handler, error) {
	// Per-learner state is keyed on Subject, the value the app's events carry too.
	var mu sync.Mutex
	streaks := map[string]int{}
	render := func(ctx context.Context, req lingaraapps.AppRenderRequest) (lingaraapps.Replier, error) {
		mu.Lock()
		streak := streaks[req.Subject]
		mu.Unlock()
		return localisedCard(req.Locale, streak)
	}
	// An action may arrive twice: make it safe to run twice.
	done := func(ctx context.Context, req lingaraapps.AppActionRequest) (lingaraapps.Replier, error) {
		mu.Lock()
		streaks[req.Subject]++
		streak := streaks[req.Subject]
		mu.Unlock()
		return localisedCard(req.Locale, streak)
	}
	app := lingaraapps.App{Render: render, Actions: map[string]lingaraapps.ActionFunc{"done": done}}
	return lingaraapps.NewHandler(app, secrets...)
}
