package app

import (
	"bufio"
	"os"

	"golang.org/x/term"
)

// Stdio is the real terminal. Interactive means stdin and stderr are both
// terminals (16.10.26ad D5 step 1; ADR 16.10.26af D2's TTY test on fds 0
// and 2), and prompts go to stderr, so `lingara apps delete x > out.json`
// still prompts.
type Stdio struct {
	reader *bufio.Reader
}

// Interactive is term.IsTerminal on fds 0 and 2.
func (s *Stdio) Interactive() bool {
	return term.IsTerminal(int(os.Stdin.Fd())) && term.IsTerminal(int(os.Stderr.Fd()))
}

// ReadLine is one line from stdin, its newline kept; false at end of input.
func (s *Stdio) ReadLine() (string, bool) {
	if s.reader == nil {
		s.reader = bufio.NewReader(os.Stdin)
	}
	line, err := s.reader.ReadString('\n')
	if err != nil && line == "" {
		return "", false
	}
	return line, true
}
