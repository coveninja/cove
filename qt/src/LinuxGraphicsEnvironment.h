#pragma once

#include <QProcessEnvironment>

namespace LinuxGraphicsEnvironment {

struct Defaults {
  bool setQpaPlatformToXcb = false;
  bool setRenderLoopToBasic = false;
};

// Computes Cove's Linux graphics defaults without mutating the process
// environment. Kept separate from applyDefaults() so compositor-specific
// workarounds can be tested without a display server or GPU.
Defaults defaultsFor(const QProcessEnvironment &environment);

// Applies only missing defaults. Explicit user values always win.
void applyDefaults();

} // namespace LinuxGraphicsEnvironment
