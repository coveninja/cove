package utils

import (
	"os"
	"path/filepath"
)

// ConfigPath returns the absolute path to filename inside the app's per-user
// config directory, creating that directory if it doesn't exist:
//
//	Linux:   $XDG_CONFIG_HOME/cove   (default ~/.config/cove)
//	Windows: %AppData%\cove          (Roaming)
//	macOS:   ~/Library/Application Support/cove
//
// User data must live here, NOT next to the executable. The AppImage is mounted
// read-only (writes beside the binary fail), and installers/auto-update replace
// the app directory on every update (which would wipe data stored alongside the
// binary).
func ConfigPath(filename string) (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	appDir := filepath.Join(dir, "cove")
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		return "", err
	}
	return filepath.Join(appDir, filename), nil
}
