package supervise

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// The process-group tests, which `make test-go-supervise` runs alone on
// the Windows runner too (16.10.26ae AC24; ADR 16.10.26af's
// go-windows-unit job): a stopped child takes its own children with it,
// and the app's environment never carries the CLI's credentials.

// treeCommand starts a child that starts a grandchild, which records its
// pid, then both wait.
func treeCommand(pidFile string) *exec.Cmd {
	if runtime.GOOS == "windows" {
		script := "start /b cmd /c \"echo grandchild>" + pidFile + " & ping -n 60 127.0.0.1 >NUL\" & ping -n 60 127.0.0.1 >NUL"
		return Shell(script)
	}
	return Shell("sh -c 'echo $$ > " + pidFile + "; exec sleep 60' & sleep 60")
}

func waitForFile(t *testing.T, path string) string {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(data)) != "" {
			return strings.TrimSpace(string(data))
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("the grandchild never started")
	return ""
}

// 22.10.26d AC3: on Windows the grandchild sits behind a nested quoted
// `cmd /c "…"`, which reaches cmd.exe only when Shell passes it verbatim.
func TestStopEndsTheWholeTree(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "pid")
	g, err := Spawn(treeCommand(pidFile))
	if err != nil {
		t.Fatal(err)
	}
	grandchild := waitForFile(t, pidFile)
	start := time.Now()
	g.Stop(2 * time.Second)
	if _, exited := g.Exited(); !exited {
		t.Fatal("the child outlived Stop")
	}
	if time.Since(start) > 10*time.Second {
		t.Fatal("Stop took too long")
	}
	if runtime.GOOS != "windows" && alive(grandchild) {
		t.Fatalf("grandchild %s outlived its group", grandchild)
	}
}

// alive reports whether a Unix pid still answers signal 0.
func alive(pid string) bool {
	time.Sleep(200 * time.Millisecond)
	return exec.Command("kill", "-0", pid).Run() == nil
}

func TestChildEnvScrubsTheCLIsCredentials(t *testing.T) {
	parent := map[string]string{"PATH": "/bin", "LINGARA_PLATFORM_CLIENT_SECRET": "s", "LINGARA_PLATFORM_CLIENT_ID": "i", "HOME": "/h"}
	env := ChildEnv(parent, [][2]string{{"PORT", "8787"}, {"HOME", "/other"}})
	joined := strings.Join(env, "\n")
	if strings.Contains(joined, "LINGARA_PLATFORM_") || !strings.Contains(joined, "PORT=8787") || !strings.Contains(joined, "HOME=/other") {
		t.Fatalf("%v", env)
	}
}

func TestAnExitedChildReportsItsStatus(t *testing.T) {
	g, err := Spawn(Shell("exit 3"))
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if state, exited := g.Exited(); exited {
			if Status(state) != "3" {
				t.Fatalf("status %s", Status(state))
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("never exited")
}

func TestCloudflaredVersionsCompareFieldByField(t *testing.T) {
	if !atLeast([]uint64{2024, 8, 2}, []uint64{2023, 4, 1}) || atLeast([]uint64{2023, 4, 0}, []uint64{2023, 4, 1}) {
		t.Fatal("dotted versions")
	}
	line := "2024-01-01T00:00:00Z INF |  https://shy-owl-1.trycloudflare.com                                 |"
	if hostnameIn(line) != "shy-owl-1.trycloudflare.com" || hostnameIn("INF https://api.trycloudflare.com.example/") != "" {
		t.Fatal("the log line names the quick tunnel")
	}
}
