//go:build windows

package supervise

import (
	"os/exec"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Shell is line through the platform shell, exactly as the developer would
// type it.
func Shell(line string) *exec.Cmd {
	// cmd.exe does not parse its command line the CommandLineToArgvW way,
	// so exec's quoting (an embedded " as \") would mangle a nested
	// `cmd /c "…"`; CmdLine hands it the line verbatim (ADR 22.10.26d D3).
	c := exec.Command("cmd")
	c.SysProcAttr = &syscall.SysProcAttr{CmdLine: `cmd /C ` + line}
	return c
}

// jobHandle is the Job Object the child is assigned to. Its
// KILL_ON_JOB_CLOSE limit ends every process in the tree when the handle
// closes, including when the CLI itself dies, so no grandchild outlives the
// session (ADR 16.10.26af D2).
type jobHandle struct {
	handle windows.Handle
}

// ownGroup starts the child in a new process group, so a console Ctrl+C
// reaches only the CLI.
func ownGroup(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= windows.CREATE_NEW_PROCESS_GROUP
}

// attachJob makes a kill-on-close job and assigns the child to it. A child
// spawned before the assignment completes could escape the job, but the
// shell (cmd /C) has not started its own children that early.
func attachJob(cmd *exec.Cmd) jobHandle {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return jobHandle{}
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, err = windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)))
	if err != nil {
		_ = windows.CloseHandle(job)
		return jobHandle{}
	}
	process, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(cmd.Process.Pid))
	if err == nil {
		_ = windows.AssignProcessToJobObject(job, process)
		_ = windows.CloseHandle(process)
	}
	return jobHandle{handle: job}
}

// terminate has no gentler signal on Windows: closing the job is the stop.
func terminate(g *Group) { kill(g) }

// kill closes the job, which ends the whole tree; a child that never joined
// a job is killed alone.
func kill(g *Group) {
	if g.job.handle != 0 {
		_ = windows.TerminateJobObject(g.job.handle, 1)
		_ = windows.CloseHandle(g.job.handle)
		g.job.handle = 0
		return
	}
	if g.Cmd.Process != nil {
		_ = g.Cmd.Process.Kill()
	}
}
