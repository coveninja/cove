#pragma once

#include <QElapsedTimer>

// Tracks post-startup backend crashes inside a bounded restart window. The
// first maxRestarts crashes may restart; subsequent crashes in the same window
// give up and surface a fatal error. An expired window starts a fresh allowance.
class RestartPolicy {
public:
  enum class Decision { Restart, GiveUp };

  explicit RestartPolicy(int maxRestarts = 3, int windowMs = 60000);

  Decision recordCrash();
  int restartCount() const { return m_restartCount; }
  int maxRestarts() const { return m_maxRestarts; }

private:
  int m_maxRestarts;
  int m_windowMs;
  int m_restartCount = 0;
  QElapsedTimer m_window;
};
