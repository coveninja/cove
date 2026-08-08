package main

import (
	"os"
	"testing"
	"time"
)

func TestManagedParentPID(t *testing.T) {
	tests := []struct {
		env     string
		wantPID int
		wantOK  bool
	}{
		{"", 0, false},
		{"0", 0, false},
		{"-1", 0, false},
		{"abc", 0, false},
		{"12345", 12345, true},
		{" 12345 ", 12345, true},
	}
	for _, tc := range tests {
		pid, ok := managedParentPID(tc.env)
		if ok != tc.wantOK || pid != tc.wantPID {
			t.Errorf("managedParentPID(%q) = (%d, %v), want (%d, %v)",
				tc.env, pid, ok, tc.wantPID, tc.wantOK)
		}
	}
}

func TestParentHasExited(t *testing.T) {
	// Our own pid cannot be our parent (Getppid != Getpid), so the first
	// condition fires regardless of whether our pid is alive.
	if !parentHasExited(os.Getpid()) {
		t.Error("own pid should be reported as exited (it is not our parent)")
	}
	// Our actual parent is running — it is the test binary's host process.
	if parentHasExited(os.Getppid()) {
		t.Error("actual parent pid should not be reported as exited")
	}
}

func TestMonitorParent(t *testing.T) {
	// os.Getpid() is not our parent (Getppid != Getpid), so parentHasExited
	// returns true on the very first poll. onExit must fire within the timeout.
	done := make(chan struct{})
	go monitorParent(os.Getpid(), time.Millisecond, func() {
		close(done)
	})
	select {
	case <-done:
		// onExit fired as expected.
	case <-time.After(time.Second):
		t.Fatal("monitorParent did not call onExit within 1 second")
	}
}
