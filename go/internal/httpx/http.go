package httpx

import (
	"encoding/base64"
	"strconv"
	"strings"
	"time"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// At most two retries of an idempotent request, waiting at most this long
// in all (16.10.26ad D6).
const (
	retryBudgetSecs = 30
	retries         = 2
)

// Pair is one query or form field, or header: name then value, kept in
// order.
type Pair [2]string

// Request is a route with its path variables, query and body. A body is
// JSON (any jsonv value) or a form; never both.
type Request struct {
	Route Route
	Vars  []Pair
	Query []Pair
	JSON  any
	Form  []Pair
}

// Auth is a request's credential: none, a bearer token, or RFC 6749
// client_secret_basic (the CI mint, D3).
type Auth struct {
	Bearer string
	BasicID,
	BasicSecret string
}

// Clock is the time a run reads and waits on, so tests never sleep.
type Clock interface {
	Now() int64
	Sleep(secs int)
}

// SystemClock is the wall clock.
type SystemClock struct{}

// Now is seconds since the epoch.
func (SystemClock) Now() int64 { return time.Now().Unix() }

// Sleep waits secs seconds.
func (SystemClock) Sleep(secs int) { time.Sleep(time.Duration(secs) * time.Second) }

// HTTP sends requests for one run.
type HTTP struct {
	Transport      Transport
	Clock          Clock
	API            string
	Portal         string
	UserAgent      string
	AcceptLanguage string
}

// URL is the route's absolute URL with vars filled and query appended.
func (h *HTTP) URL(route Route, vars, query []Pair) string {
	base := h.API
	if route.Base == BasePortal {
		base = h.Portal
	}
	path := route.Path
	for _, v := range vars {
		path = strings.ReplaceAll(path, "{"+v[0]+"}", Encode(v[1]))
	}
	if len(query) > 0 {
		path += "?" + Form(query)
	}
	return strings.TrimRight(base, "/") + path
}

func (h *HTTP) wire(req Request, auth Auth) WireRequest {
	headers := []Pair{{"user-agent", h.UserAgent}, {"accept", "application/json"}}
	if h.AcceptLanguage != "" {
		headers = append(headers, Pair{"accept-language", h.AcceptLanguage})
	}
	switch {
	case auth.Bearer != "":
		headers = append(headers, Pair{"authorization", "Bearer " + auth.Bearer})
	case auth.BasicID != "":
		headers = append(headers, Pair{"authorization", basic(auth.BasicID, auth.BasicSecret)})
	}
	var body []byte
	switch {
	case req.JSON != nil:
		headers = append(headers, Pair{"content-type", "application/json"})
		body = []byte(jsonv.Marshal(req.JSON))
	case req.Form != nil:
		headers = append(headers, Pair{"content-type", "application/x-www-form-urlencoded"})
		body = []byte(Form(req.Form))
	}
	return WireRequest{Method: req.Route.Method, URL: h.URL(req.Route, req.Vars, req.Query), Headers: headers, Body: body}
}

// Send is one request, with D6's retries: a GET, PUT, PATCH or DELETE
// answered 429 or 503 with Retry-After is retried up to twice within 30 s.
// A POST is never retried after a response (B5).
func (h *HTTP) Send(req Request, auth Auth) (WireResponse, *core.Fail) {
	wire := h.wire(req, auth)
	waited := 0
	for attempt := 0; ; attempt++ {
		resp, err := h.Transport.Send(wire)
		if err != nil {
			return resp, core.NewFail(core.ExitUnavailable, "unavailable", req.Route.Name+" could not be reached: "+err.Error())
		}
		if attempt > 0 && replayedDeleteDone(wire, resp) {
			return WireResponse{Status: 204}, nil
		}
		secs, ok := retryWait(wire, resp)
		if !ok || attempt >= retries || waited+secs > retryBudgetSecs {
			return resp, nil
		}
		h.Clock.Sleep(secs)
		waited += secs
	}
}

// retryWait is the wait an idempotent request may retry after.
func retryWait(wire WireRequest, resp WireResponse) (int, bool) {
	idempotent := wire.Method == "GET" || wire.Method == "PUT" || wire.Method == "PATCH" || wire.Method == "DELETE"
	busy := resp.Status == 429 || resp.Status == 503
	if !idempotent || !busy {
		return 0, false
	}
	return RetryAfter(resp)
}

// RetryAfter is the response's Retry-After seconds, if it carries them.
func RetryAfter(resp WireResponse) (int, bool) {
	v, ok := resp.Header("retry-after")
	if !ok {
		return 0, false
	}
	secs, err := strconv.ParseUint(strings.TrimSpace(v), 10, 31)
	return int(secs), err == nil
}

// replayedDeleteDone is a DELETE replayed after it succeeded: a coded 404
// (D6).
func replayedDeleteDone(wire WireRequest, resp WireResponse) bool {
	v, err := jsonv.Parse(resp.Body)
	return err == nil && wire.Method == "DELETE" && resp.Status == 404 && jsonv.AsObject(v).Has("code")
}

// Encode passes RFC 3986's unreserved characters; everything else is %XX.
func Encode(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || strings.IndexByte("-._~", c) >= 0 {
			b.WriteByte(c)
		} else {
			b.WriteString("%" + strings.ToUpper(strconv.FormatUint(uint64(c)|0x100, 16)[1:]))
		}
	}
	return b.String()
}

// Form is pairs as application/x-www-form-urlencoded, in order.
func Form(pairs []Pair) string {
	parts := make([]string, len(pairs))
	for i, p := range pairs {
		parts[i] = Encode(p[0]) + "=" + Encode(p[1])
	}
	return strings.Join(parts, "&")
}

func basic(id, secret string) string {
	raw := Encode(id) + ":" + Encode(secret)
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(raw))
}
