package apps

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// The five app_dev.* routes (Backend 16.10.26v §12; ADR 16.10.26ae R1–R3),
// one function each, plus the read-then-patch every writer of the dev
// version uses: a 409 version_conflict re-reads once and retries, and a
// second one is the server's refused.

// devSession is what an open answers: the session, and the secrets it
// minted, which live only in this process's memory and the app's
// environment (D5).
type devSession struct {
	id           string
	leaseSecs    uint64
	expiresAt    string
	appSecret    string
	clientSecret string
	hasClient    bool
}

func devCall(c *app.Ctx, route string, vars []httpx.Pair, body any) (any, *core.Fail) {
	return c.Call(httpx.Request{Route: httpx.Fixed(route), Vars: vars, JSON: body})
}

// devGet is GET …/dev: the manifest, render_url, draft_version, the session.
func devGet(c *app.Ctx, id string) (any, *core.Fail) {
	return devCall(c, "app_dev.get", []httpx.Pair{{"id", id}}, nil)
}

func devPatch(c *app.Ctx, id string, body any) (any, *core.Fail) {
	return devCall(c, "app_dev.patch", []httpx.Pair{{"id", id}}, body)
}

// devPatchWithRetry reads draft_version, then patches build(draft_version);
// one re-read and retry on version_conflict.
func devPatchWithRetry(c *app.Ctx, id string, build func(draftVersion any) any) (any, *core.Fail) {
	view, fail := devGet(c, id)
	if fail != nil {
		return nil, fail
	}
	answer, fail := devPatch(c, id, build(jsonv.Get(view, "draft_version")))
	if fail == nil || fail.Code != "version_conflict" {
		return answer, fail
	}
	if view, fail = devGet(c, id); fail != nil {
		return nil, fail
	}
	return devPatch(c, id, build(jsonv.Get(view, "draft_version")))
}

// devOpen is POST …/dev/session {take_over, credentials}.
func devOpen(c *app.Ctx, id string, takeOver, client bool) (devSession, *core.Fail) {
	body := jsonv.Obj("take_over", takeOver)
	if client {
		body.Set("credentials", []string{"client"})
	}
	answer, fail := devCall(c, "app_dev.session_open", []httpx.Pair{{"id", id}}, body)
	if fail != nil {
		return devSession{}, fail
	}
	s := devSession{
		id:        jsonv.Text(answer, "session_id"),
		leaseSecs: 300,
		expiresAt: jsonv.Text(answer, "expires_at"),
		appSecret: jsonv.Text(answer, "app_secret", "secret"),
	}
	if n, ok := jsonv.Uint(jsonv.Get(answer, "lease_secs")); ok && n > 0 {
		s.leaseSecs = n
	}
	s.clientSecret, s.hasClient = jsonv.Str(jsonv.Get(answer, "client_secret", "secret"))
	return s, nil
}

func devRenew(c *app.Ctx, id, sessionID string) *core.Fail {
	_, fail := devCall(c, "app_dev.session_renew", []httpx.Pair{{"id", id}, {"session_id", sessionID}}, nil)
	return fail
}

// devEnd is DELETE …/dev/session/{sid}: parks, keeps render_url, revokes
// the session's credentials.
func devEnd(c *app.Ctx, id, sessionID string) {
	_, _ = devCall(c, "app_dev.session_end", []httpx.Pair{{"id", id}, {"session_id", sessionID}}, nil)
}
