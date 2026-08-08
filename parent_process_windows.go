//go:build windows

package main

import "golang.org/x/sys/windows"

// processAlive reports whether pid is still running on Windows. We open with
// SYNCHRONIZE — the minimal right needed to wait on a handle — then poll with
// a zero timeout. WAIT_TIMEOUT means the process is still running;
// WAIT_OBJECT_0 means it has exited. A failed OpenProcess means the pid is
// already gone.
func processAlive(pid int) bool {
	h, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
	if err != nil {
		return false
	}
	defer windows.CloseHandle(h)
	s, _ := windows.WaitForSingleObject(h, 0)
	// windows.WAIT_TIMEOUT is syscall.Errno; WaitForSingleObject returns uint32.
	return s == uint32(windows.WAIT_TIMEOUT)
}
