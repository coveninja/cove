package utils

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
)

// dataDirOverride, when non-nil, replaces os.UserConfigDir()/cove as the
// base directory for ConfigPath. Set once at startup via SetDataDir (mobile
// builds, custom COVE_DATA_DIR) before any package calls ConfigPath. An
// atomic pointer avoids any data-race flag from the race detector if startup
// code and a very early request ever overlap — in practice SetDataDir is
// called before any goroutines start serving.
var dataDirOverride atomic.Pointer[string]

// SetDataDir overrides the directory ConfigPath resolves filenames into.
// Call once, before any package initialises, when the platform-default
// os.UserConfigDir is unavailable or undesirable (Android, custom data
// paths). An empty dir is a no-op (leaves the platform default in place).
func SetDataDir(dir string) {
	if dir != "" {
		dataDirOverride.Store(&dir)
	}
}

// ConfigPath returns the absolute path to filename inside the app's per-user
// config directory, creating that directory if it doesn't exist:
//
//	Linux:   $XDG_CONFIG_HOME/cove   (default ~/.config/cove)
//	Windows: %AppData%\cove          (Roaming)
//	macOS:   ~/Library/Application Support/cove
//
// When SetDataDir has been called (mobile builds where os.UserConfigDir fails,
// or COVE_DATA_DIR overrides the default on desktop), that directory is used
// directly instead.
//
// User data must live here, NOT next to the executable. The AppImage is mounted
// read-only (writes beside the binary fail), and installers/auto-update replace
// the app directory on every update (which would wipe data stored alongside the
// binary).
func ConfigPath(filename string) (string, error) {
	clean := filepath.Clean(filename)
	if filename == "" ||
		clean == "." ||
		filepath.IsAbs(clean) ||
		filepath.VolumeName(clean) != "" ||
		clean == ".." ||
		strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("invalid config filename %q", filename)
	}

	var appDir string
	if p := dataDirOverride.Load(); p != nil {
		appDir = *p
	} else {
		dir, err := os.UserConfigDir()
		if err != nil {
			return "", err
		}
		appDir = filepath.Join(dir, "cove")
	}
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		return "", err
	}
	return filepath.Join(appDir, clean), nil
}
