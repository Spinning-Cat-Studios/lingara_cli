package supervise

import (
	"bufio"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
)

// cloudflared (ADR 16.10.26ae D8). Lookup: LINGARA_CLOUDFLARED and then
// only that path, else cloudflared on PATH, refused below tools.toml's
// minimum. The URL, readiness and respawn live in tunnel.go.

// Missing is tool_missing with D8's hint, the exit row's own message.
func Missing() *core.Fail {
	return core.Message(core.ExitToolMissing, "tool_missing_cloudflared")
}

func onPath(name string, env core.Env) string {
	path, ok := env.Get("PATH")
	if !ok {
		return ""
	}
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	for _, dir := range filepath.SplitList(path) {
		candidate := filepath.Join(dir, name)
		if isFile(candidate) {
			return candidate
		}
	}
	return ""
}

func isFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

// versionOf is the first dotted version (two fields or more) on
// `cloudflared --version`'s first line.
func versionOf(binary string) []uint64 {
	cmd := exec.Command(binary, "--version")
	out, err := cmd.Output()
	if err != nil && len(out) == 0 {
		return nil
	}
	first, _, _ := strings.Cut(string(out), "\n")
	for _, w := range strings.Fields(first) {
		if v := assets.DottedVersion(w); len(v) >= 2 {
			return v
		}
	}
	return nil
}

// atLeast compares dotted versions field by field, as Rust's Vec ordering.
func atLeast(v, min []uint64) bool {
	for i := 0; i < len(v) && i < len(min); i++ {
		if v[i] != min[i] {
			return v[i] > min[i]
		}
	}
	return len(v) >= len(min)
}

// FindCloudflared is lookup and version check, offline (D7 step 2).
func FindCloudflared(env core.Env) (string, *core.Fail) {
	binary := onPath("cloudflared", env)
	if explicit, ok := env.Get("LINGARA_CLOUDFLARED"); ok {
		binary = ""
		if isFile(explicit) {
			binary = explicit
		}
	}
	if binary == "" {
		return "", Missing()
	}
	if v := versionOf(binary); v == nil || !atLeast(v, assets.CloudflaredMinVersion()) {
		return "", Missing()
	}
	return binary, nil
}

// freePort is a free loopback port, for cloudflared's metrics server.
func freePort() (int, *core.Fail) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, core.Failure("a free port: " + err.Error())
	}
	defer func() { _ = l.Close() }()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// hostnameIn is the https://…trycloudflare.com hostname on one log line.
func hostnameIn(line string) string {
	at := strings.Index(line, "https://")
	if at < 0 {
		return ""
	}
	rest := line[at+len("https://"):]
	end := 0
	for end < len(rest) && (isHostChar(rest[end])) {
		end++
	}
	host := rest[:end]
	if !strings.HasSuffix(host, ".trycloudflare.com") {
		return ""
	}
	return host
}

func isHostChar(c byte) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.'
}

// keep reads cloudflared's stderr: the last keptLines lines, for
// tunnel_failed's reason, and the first quick-tunnel hostname it logs.
func (t *Tunnel) keep(r *os.File) {
	defer func() { _ = r.Close() }()
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := scanner.Text()
		t.mu.Lock()
		if host := hostnameIn(line); host != "" && t.loggedHost == "" {
			t.loggedHost = host
		}
		t.lines = append(t.lines, line)
		if len(t.lines) > keptLines {
			t.lines = t.lines[len(t.lines)-keptLines:]
		}
		t.mu.Unlock()
	}
}

func metricsAddress(port int) string {
	return "127.0.0.1:" + strconv.Itoa(port)
}
