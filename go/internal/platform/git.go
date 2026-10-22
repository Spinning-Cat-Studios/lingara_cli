package platform

import (
	"os/exec"
	"path/filepath"
)

// D4's two git questions: is a file tracked (a warning), and does git
// ignore a path that would hold secrets (a refusal). Outside a work tree,
// or with no git on PATH, there is no index to commit a file into, so both
// answers are the permissive ones and the checks are skipped.

// git runs `git -C dir args…` quietly; false when it could not run or
// failed.
func git(dir string, args ...string) (succeeded, ran bool) {
	cmd := exec.Command("git", append([]string{"-C", dir}, args...)...)
	err := cmd.Run()
	if err == nil {
		return true, true
	}
	_, exited := err.(*exec.ExitError)
	return false, exited
}

func inWorkTree(dir string) bool {
	ok, _ := git(dir, "rev-parse", "--is-inside-work-tree")
	return ok
}

// Tracked reports whether git tracks path; false outside a work tree.
func Tracked(path string) bool {
	dir := filepath.Dir(path)
	if !inWorkTree(dir) {
		return false
	}
	ok, _ := git(dir, "ls-files", "--error-unmatch", "--", filepath.Base(path))
	return ok
}

// Ignored reports whether git ignores path; true outside a work tree, where
// nothing can be committed.
func Ignored(path string) bool {
	dir := filepath.Dir(path)
	if !inWorkTree(dir) {
		return true
	}
	ok, _ := git(dir, "check-ignore", "-q", "--", filepath.Base(path))
	return ok
}
