package core

import (
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Ctrl+C and SIGTERM, trapped once for the whole process (ADR 16.10.26af
// D9: Go dies on SIGINT without signal.Notify). A wait that can end early,
// apps dev's session above all, claims the signal and polls Stopped, so it
// parks the session and stops its children before it returns exit 130.
// With no claim, nothing is mid-cleanup, so the handler's fallback ends
// the run with 130 at once: still an exit code, never a death by signal.

var (
	stopped  atomic.Bool
	claims   atomic.Int32
	watching sync.Once
)

// WatchSignals installs the handler; unclaimed runs for a signal that
// arrives while nothing has claimed one.
func WatchSignals(unclaimed func()) {
	watching.Do(func() {
		ch := make(chan os.Signal, 2)
		signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
		go func() {
			for range ch {
				stopped.Store(true)
				if claims.Load() == 0 && unclaimed != nil {
					unclaimed()
				}
			}
		}()
	})
}

// Claim says a wait will see the signal through Stopped; release when it is
// over.
func Claim() (release func()) {
	claims.Add(1)
	return func() { claims.Add(-1) }
}

// Stopped reports whether Ctrl+C or SIGTERM has arrived.
func Stopped() bool {
	return stopped.Load()
}

// ResetStopped forgets a delivered signal, for tests that send several.
func ResetStopped() {
	stopped.Store(false)
}

// SleepUnlessStopped waits d a tick at a time; Interrupted if a signal
// arrives first.
func SleepUnlessStopped(d time.Duration) *Fail {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if Stopped() {
			return Interrupted()
		}
		time.Sleep(min(50*time.Millisecond, time.Until(deadline)))
	}
	if Stopped() {
		return Interrupted()
	}
	return nil
}
