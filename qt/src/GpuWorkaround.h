#pragma once

// Reactive mitigation for the Qt 6.9+ WebEngine GBM regression on Wayland:
// Chromium's GPU service aborts the whole process when cross-device GBM
// buffer import fails (gbm_wrapper.cc, worst on multi-GPU systems). Instead
// of forcing a workaround on every system — the fallback paths have bugs of
// their own — each launch writes a sentinel that is cleared once startup
// proves healthy; if the sentinel survives, the previous launch died and the
// next one escalates:
//
//   0 (Default) - no override; normal WebEngine behaviour
//   1 (NoGbm)   - QTWEBENGINE_FORCE_USE_GBM=0 (Chromium falls back to Vulkan)
//   2 (NoGpu)   - level 1 + --disable-gpu in QTWEBENGINE_CHROMIUM_FLAGS
//                 (software raster for the web layer; mpv has its own GL path
//                 and is unaffected)
//
// State lives next to shell.log in <GenericConfigLocation>/cove/:
//   gpu_workaround.ini - persisted level + the Qt version it was probed on
//   gpu_starting.lock  - the startup sentinel (existence is the crash signal)
namespace GpuWorkaround {

enum class Level { Default = 0, NoGbm = 1, NoGpu = 2 };

struct ApplyResult {
  Level level = Level::Default;
  int storedLevel = 0;        // level read from the ini before escalation
  bool sentinelFound = false; // previous launch died before proving healthy
  bool wasEscalated = false;  // level was bumped above storedLevel
  bool wasOverridden = false; // pinned via COVE_GPU_WORKAROUND/--gpu-workaround
};

// Decide the effective level and apply its environment variables. Must run
// before QtWebEngineQuick::initialize() and therefore works without a
// QCoreApplication. Read-only on disk — persisting is deferred to
// commitState() so an "already running" second instance (which exits before
// app.exec() and never fires aboutToQuit) can't corrupt the state.
// overrideLevel 0..2 pins the level (skips escalation, never persisted);
// -1 resolves COVE_GPU_WORKAROUND from the environment instead.
ApplyResult applyBeforeInit(int overrideLevel = -1);

// Persist the escalated level (unless overridden) and create the startup
// sentinel. Call once the single-instance lock is held — Chromium's GPU
// service only spawns when the WebEngineView loads, so the crash window
// opens later than this.
void commitState(const ApplyResult &state);

// Remove the startup sentinel. Idempotent. Called from a 30 s post-load
// timer and from aboutToQuit so a quick clean exit isn't mistaken for a
// crash.
void markStartupSuccessful();

} // namespace GpuWorkaround
