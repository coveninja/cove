//go:build !windows

package main

import (
	"os"
	"syscall"
)

// processAlive reports whether pid is still running. Signal 0 asks the kernel
// to validate the pid without delivering anything. EPERM means the target
// exists but we lack permission to signal it — still alive. Any other non-nil
// error (ESRCH, os.ErrProcessDone) means the process is gone.
func processAlive(pid int) bool {
	p, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	err = p.Signal(syscall.Signal(0))
	return err == nil || err == syscall.EPERM
}
