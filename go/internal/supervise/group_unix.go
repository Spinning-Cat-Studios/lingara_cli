//go:build !windows

package supervise

import (
	"os/exec"
	"syscall"
)

// Shell is line through the platform shell, exactly as the developer would
// type it.
func Shell(line string) *exec.Cmd { return exec.Command("sh", "-c", line) }

// jobHandle is Windows' Job Object; Unix groups by process group instead.
type jobHandle struct{}

// ownGroup starts the child as the leader of a new process group, so the
// terminal's Ctrl+C reaches only the CLI, which stops the group itself in
// its own order (16.10.26ae D7 step 8).
func ownGroup(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Setpgid = true
}

func attachJob(*exec.Cmd) jobHandle { return jobHandle{} }

func signalGroup(g *Group, sig syscall.Signal) {
	if g.Cmd.Process != nil {
		_ = syscall.Kill(-g.Cmd.Process.Pid, sig)
	}
}

func terminate(g *Group) { signalGroup(g, syscall.SIGTERM) }

func kill(g *Group) { signalGroup(g, syscall.SIGKILL) }
