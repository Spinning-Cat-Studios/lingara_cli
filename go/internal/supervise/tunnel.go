package supervise

import (
	"io"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// One cloudflared incarnation (ADR 16.10.26ae D8). URL: the CLI picks a
// free loopback port for --metrics and polls its /quicktunnel, falling back
// to the https://…trycloudflare.com line on stderr. Ready: the probe URL
// (LINGARA_TUNNEL_PROBE_URL, default https://{hostname}/) answers anything
// but Cloudflare's 530. Each wait is 30 s.

const (
	tunnelWait   = 30 * time.Second
	pollEvery    = 250 * time.Millisecond
	keptLines    = 20
	defaultProbe = "https://{hostname}/"
)

// Tunnel is one running cloudflared.
type Tunnel struct {
	group      *Group
	metrics    string
	mu         sync.Mutex
	lines      []string
	loggedHost string
	client     *http.Client
}

// SpawnTunnel is `cloudflared tunnel --url http://127.0.0.1:<port>
// --metrics 127.0.0.1:<free>`.
func SpawnTunnel(binary string, port int) (*Tunnel, *core.Fail) {
	free, fail := freePort()
	if fail != nil {
		return nil, fail
	}
	t := &Tunnel{metrics: metricsAddress(free), client: &http.Client{
		Timeout:       2 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
	cmd := exec.Command(binary, "tunnel", "--url", "http://127.0.0.1:"+strconv.Itoa(port), "--metrics", t.metrics)
	group, stderr, err := SpawnCapturing(cmd, false)
	if err != nil {
		return nil, core.NewFail(core.ExitTunnelFailed, "tunnel_failed", "cloudflared: "+err.Error())
	}
	t.group = group
	go t.keep(stderr)
	return t, nil
}

// Alive reports whether cloudflared is still running.
func (t *Tunnel) Alive() bool {
	_, exited := t.group.Exited()
	return !exited
}

// Tail is the last lines cloudflared wrote, for tunnel_failed's reason.
func (t *Tunnel) Tail() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return strings.Join(t.lines, "\n")
}

func (t *Tunnel) quickTunnel() string {
	resp, err := t.client.Get("http://" + t.metrics + "/quicktunnel")
	if err != nil {
		return ""
	}
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return ""
	}
	v, err := jsonv.Parse(body)
	if err != nil {
		return ""
	}
	return jsonv.Text(v, "hostname")
}

func (t *Tunnel) logged() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.loggedHost
}

// Hostname is the quick tunnel's hostname: metrics first, the log line
// second; "" when neither came within 30 s, cloudflared died, or stop said
// so.
func (t *Tunnel) Hostname(stop func() bool) string {
	deadline := time.Now().Add(tunnelWait)
	for time.Now().Before(deadline) && t.Alive() && !stop() {
		if host := t.quickTunnel(); host != "" {
			return host
		}
		if host := t.logged(); host != "" {
			return host
		}
		time.Sleep(pollEvery)
	}
	return ""
}

// Ready reports whether the probe reached the app through the tunnel: any
// answer but a 530, once a second, for at most 30 s.
func (t *Tunnel) Ready(host, template string, stop func() bool) bool {
	if template == "" {
		template = defaultProbe
	}
	url := strings.ReplaceAll(strings.ReplaceAll(template, "{hostname}", host), "{metrics}", t.metrics)
	deadline := time.Now().Add(tunnelWait)
	for time.Now().Before(deadline) && t.Alive() && !stop() {
		if resp, err := t.client.Get(url); err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode != 530 {
				return true
			}
		}
		time.Sleep(time.Second)
	}
	return false
}

// Stop stops cloudflared's group, with a 5 s grace.
func (t *Tunnel) Stop() {
	t.group.Stop(5 * time.Second)
}
