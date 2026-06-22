//go:build !windows

package player

import "syscall"

// lowerPriority nices a child process (e.g. the transcoder) down so it doesn't
// starve the rest of the system. Unix-only — syscall.Setpriority and
// PRIO_PROCESS don't exist on Windows (see priority_windows.go).
//
// Adjust the niceness (10) to match whatever hls.go used before.
func lowerPriority(pid int) error {
	return syscall.Setpriority(syscall.PRIO_PROCESS, pid, 10)
}
