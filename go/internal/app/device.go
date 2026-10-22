package app

import (
	"time"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// login --device (RFC 8628, 16.10.26u §7): for SSH sessions and machines
// without a browser. authorization_pending keeps polling, slow_down adds
// 5 s, expired_token and access_denied are auth_required, and any answer
// carrying B4's lingara_code is platform_disabled (D8).

const deviceGrant = "urn:ietf:params:oauth:grant-type:device_code"

type deviceStart struct {
	code      string
	interval  int
	expiresIn int
}

func readStart(v any) deviceStart {
	s := deviceStart{code: jsonv.Text(v, "device_code"), interval: 5, expiresIn: 900}
	if n, ok := jsonv.Uint(jsonv.Get(v, "interval")); ok {
		s.interval = int(n)
	}
	if n, ok := jsonv.Uint(jsonv.Get(v, "expires_in")); ok {
		s.expiresIn = int(n)
	}
	return s
}

// DeviceLogin is the token response once the developer has approved the
// code.
func (c *Ctx) DeviceLogin() (any, *core.Fail) {
	clientID := c.App.Endpoints.ClientID
	form := []httpx.Pair{{"client_id", clientID}, {"scope", Scopes}}
	start, fail := c.TokenPost("oauth.device_authorization", form, httpx.Auth{})
	if fail != nil {
		return nil, fail
	}
	c.Out.Note(assets.Message("login_device_code",
		"verification_uri", jsonv.Text(start, "verification_uri"), "user_code", jsonv.Text(start, "user_code")))
	if complete, ok := jsonv.Str(jsonv.Get(start, "verification_uri_complete")); ok {
		c.Out.Note(complete)
	}
	release := core.Claim()
	defer release()
	return c.poll(readStart(start), clientID)
}

func (c *Ctx) wait(secs int) *core.Fail {
	if _, real := c.App.HTTP.Clock.(httpx.SystemClock); real {
		return core.SleepUnlessStopped(time.Duration(secs) * time.Second)
	}
	c.App.HTTP.Clock.Sleep(secs)
	return nil
}

func (c *Ctx) poll(s deviceStart, clientID string) (any, *core.Fail) {
	route := httpx.Fixed("oauth.token")
	waited := 0
	for {
		if fail := c.wait(s.interval); fail != nil {
			return nil, fail
		}
		waited += s.interval
		form := []httpx.Pair{{"grant_type", deviceGrant}, {"device_code", s.code}, {"client_id", clientID}}
		reply, fail := c.App.HTTP.Send(httpx.Request{Route: route, Form: form}, httpx.Auth{})
		if fail != nil {
			return nil, fail
		}
		if ok(reply.Status) {
			v, err := jsonv.Parse(reply.Body)
			if err != nil {
				return nil, core.Failure("unexpected response from oauth.token")
			}
			return v, nil
		}
		if done, fail := pollAnswer(route, reply, &s, waited); done {
			return nil, fail
		}
	}
}

// pollAnswer reads one refused poll: keep polling (false), or the refusal.
func pollAnswer(route httpx.Route, reply httpx.WireResponse, s *deviceStart, waited int) (bool, *core.Fail) {
	body, _ := jsonv.Parse(reply.Body)
	errorCode := ""
	if !jsonv.AsObject(body).Has("lingara_code") {
		errorCode = jsonv.Text(body, "error")
	}
	pending := errorCode == "authorization_pending" || errorCode == "slow_down"
	switch {
	case pending && waited < s.expiresIn:
		if errorCode == "slow_down" {
			s.interval += 5
		}
		return false, nil
	case pending || errorCode == "expired_token":
		return true, core.Auth("the code expired before it was approved; run `lingara login --device` again")
	case errorCode == "access_denied":
		return true, core.NewFail(core.ExitAuthRequired, "access_denied", "sign-in was declined")
	}
	return true, Refusal(route, reply, false)
}
