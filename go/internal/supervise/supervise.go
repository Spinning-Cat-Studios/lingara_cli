// Package supervise is the children apps dev runs (ADR 16.10.26ae D5, D7
// steps 4 and 8; 16.10.26af D2's Go row): the app's environment, built from
// the parent's with the CLI's own platform credentials scrubbed; the
// platform shell (sh -c, cmd /C); a process group per child on Unix, so a
// terminal's Ctrl+C reaches only the CLI, and a Job Object with
// KILL_ON_JOB_CLOSE on Windows, so the tree dies with the job; a stop that
// signals the whole group, SIGTERM then SIGKILL after a grace period; and
// cloudflared's lifecycle.
package supervise

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ScrubbedPrefix is the CLI's own credentials, never the app's (D5).
const ScrubbedPrefix = "LINGARA_PLATFORM_"

// ChildEnv is the parent's environment, minus the CLI's credentials, plus
// extra (later entries win), as KEY=VALUE lines sorted by name.
func ChildEnv(parent map[string]string, extra [][2]string) []string {
	env := map[string]string{}
	for k, v := range parent {
		if !strings.HasPrefix(k, ScrubbedPrefix) {
			env[k] = v
		}
	}
	for _, kv := range extra {
		env[kv[0]] = kv[1]
	}
	keys := make([]string, 0, len(env))
	for k := range env {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]string, len(keys))
	for i, k := range keys {
		out[i] = k + "=" + env[k]
	}
	return out
}

// Group is a running child that leads its own process group (a Job Object
// on Windows). Its exit is watched by one goroutine, so polling it never
// blocks.
type Group struct {
	Cmd   *exec.Cmd
	job   jobHandle
	done  chan struct{}
	state *os.ProcessState
	mu    sync.Mutex
}

// Spawn starts cmd in a group of its own.
func Spawn(cmd *exec.Cmd) (*Group, error) {
	ownGroup(cmd)
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	g := &Group{Cmd: cmd, done: make(chan struct{})}
	g.job = attachJob(cmd)
	go func() {
		_ = cmd.Wait()
		g.mu.Lock()
		g.state = cmd.ProcessState
		g.mu.Unlock()
		close(g.done)
	}()
	return g, nil
}

// Exited is the exit, once the child has exited.
func (g *Group) Exited() (*os.ProcessState, bool) {
	select {
	case <-g.done:
		g.mu.Lock()
		defer g.mu.Unlock()
		return g.state, true
	default:
		return nil, false
	}
}

// SpawnCapturing starts cmd with its stderr, and its stdout too when
// withStdout, on a pipe the caller reads to its end. The pipe is the CLI's
// own rather than exec's StdoutPipe, which Wait would close while a reader
// still has lines to relay.
func SpawnCapturing(cmd *exec.Cmd, withStdout bool) (*Group, *os.File, error) {
	r, w, err := os.Pipe()
	if err != nil {
		return nil, nil, err
	}
	cmd.Stderr = w
	if withStdout {
		cmd.Stdout = w
	}
	g, err := Spawn(cmd)
	_ = w.Close()
	if err != nil {
		_ = r.Close()
		return nil, nil, err
	}
	return g, r, nil
}

// Stop signals the whole group: SIGTERM, then SIGKILL once grace passes
// (the group may outlive its leader, so whatever is left of it goes too).
// On Windows the job is closed, which kills the tree at once.
func (g *Group) Stop(grace time.Duration) {
	terminate(g)
	select {
	case <-g.done:
	case <-time.After(grace):
	}
	kill(g)
	<-g.done
}

// Status is the exit's B1 spelling: the code, or "a signal".
func Status(state *os.ProcessState) string {
	if state == nil || state.ExitCode() < 0 {
		return "a signal"
	}
	return strconv.Itoa(state.ExitCode())
}

// Relay copies source to stderr line by line, each line after prefix,
// until the source ends.
func Relay(source io.Reader, prefix string) {
	go func() {
		scanner := bufio.NewScanner(source)
		scanner.Buffer(make([]byte, 64*1024), 1024*1024)
		for scanner.Scan() {
			fmt.Fprintln(os.Stderr, prefix+scanner.Text())
		}
	}()
}

// WaitListening reports whether 127.0.0.1:port accepts a connection before
// timeout, giving up early when stop says so.
func WaitListening(port int, timeout time.Duration, stop func() bool) bool {
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if conn, err := net.DialTimeout("tcp", address, 250*time.Millisecond); err == nil {
			_ = conn.Close()
			return true
		}
		if stop() {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
	return false
}
