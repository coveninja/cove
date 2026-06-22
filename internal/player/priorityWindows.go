//go:build windows

package player

// lowerPriority is a no-op on Windows. syscall.Setpriority / PRIO_PROCESS are
// Unix-only. If you ever want the real equivalent, use
// golang.org/x/sys/windows.SetPriorityClass(handle, BELOW_NORMAL_PRIORITY_CLASS)
// with the process handle (not just the PID).
func lowerPriority(pid int) error {
	_ = pid
	return nil
}
