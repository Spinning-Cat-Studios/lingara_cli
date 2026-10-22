package app

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
)

// login's default: PKCE over a loopback redirect (RFC 8252, 16.10.26u §6).
// The kit binds 127.0.0.1:0, opens the portal's authorize page, and accepts
// exactly one callback whose state matches and whose iss is the portal base
// (RFC 9207). The listener gives up after 5 minutes, and Ctrl+C ends the
// wait with exit 130.

const listenFor = 5 * time.Minute

const closeTab = "<!doctype html><title>Lingara</title><p>You can close this tab.</p>"

func randomText() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// Challenge is S256's code_challenge for verifier (RFC 7636).
func Challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// BrowserLogin is the token response for the code the browser brought back.
func (c *Ctx) BrowserLogin() (any, *core.Fail) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, core.Failure("could not listen on 127.0.0.1: " + err.Error())
	}
	defer func() { _ = listener.Close() }()
	port := listener.Addr().(*net.TCPAddr).Port
	verifier, state := randomText(), randomText()
	redirect := "http://127.0.0.1:" + strconv.Itoa(port) + "/callback"
	clientID := c.App.Endpoints.ClientID
	query := []httpx.Pair{
		{"response_type", "code"}, {"client_id", clientID}, {"redirect_uri", redirect},
		{"code_challenge", Challenge(verifier)}, {"code_challenge_method", "S256"}, {"state", state}, {"scope", Scopes},
	}
	authorize := c.App.HTTP.URL(httpx.Fixed("oauth.authorize"), nil, query)
	c.Out.Note(authorize)
	c.Out.Note(assets.Message("login_browser_wait"))
	c.App.Browser(authorize)
	issuer := strings.TrimRight(c.App.HTTP.Portal, "/")
	code, fail := waitForCallback(listener, state, issuer)
	if fail != nil {
		return nil, fail
	}
	form := []httpx.Pair{
		{"grant_type", "authorization_code"}, {"code", code}, {"redirect_uri", redirect},
		{"code_verifier", verifier}, {"client_id", clientID},
	}
	return c.TokenPost("oauth.token", form, httpx.Auth{})
}

// waitForCallback accepts until the one callback arrives, the deadline
// passes, or a signal stops the wait.
func waitForCallback(listener net.Listener, state, issuer string) (string, *core.Fail) {
	release := core.Claim()
	defer release()
	tcp := listener.(*net.TCPListener)
	deadline := time.Now().Add(listenFor)
	for time.Now().Before(deadline) {
		if core.Stopped() {
			return "", core.Interrupted()
		}
		_ = tcp.SetDeadline(time.Now().Add(100 * time.Millisecond))
		conn, err := tcp.Accept()
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			continue
		}
		if err != nil {
			return "", core.Failure("the loopback listener failed: " + err.Error())
		}
		if code, done := answerCallback(conn, state, issuer); done {
			if code == "" {
				return "", core.NewFail(core.ExitAuthRequired, "access_denied", "sign-in was declined")
			}
			return code, nil
		}
	}
	return "", core.Auth("no sign-in arrived within 5 minutes; run `lingara login` again")
}

// answerCallback answers one request on the loopback; the code, and whether
// this was the callback ("" with true: the developer declined).
func answerCallback(conn net.Conn, state, issuer string) (string, bool) {
	defer func() { _ = conn.Close() }()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 8192)
	n, _ := conn.Read(buf)
	fields := strings.Fields(string(buf[:n]))
	target := ""
	if len(fields) > 1 {
		target = fields[1]
	}
	path, rawQuery, _ := strings.Cut(target, "?")
	params, _ := url.ParseQuery(rawQuery)
	ours := path == "/callback" && params.Get("state") == state && params.Has("state") &&
		params.Has("iss") && strings.TrimRight(params.Get("iss"), "/") == issuer
	status := "400 Bad Request"
	if ours {
		status = "200 OK"
	}
	_, _ = fmt.Fprintf(conn, "HTTP/1.1 %s\r\ncontent-type: text/html\r\ncontent-length: %d\r\nconnection: close\r\n\r\n%s",
		status, len(closeTab), closeTab)
	if !ours {
		return "", false
	}
	return params.Get("code"), true
}
