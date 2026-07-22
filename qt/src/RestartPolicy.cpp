#include "RestartPolicy.h"

#include <QtGlobal>

RestartPolicy::RestartPolicy(int maxRestarts, int windowMs)
    : m_maxRestarts(qMax(0, maxRestarts)), m_windowMs(qMax(1, windowMs)) {}

RestartPolicy::Decision RestartPolicy::recordCrash() {
  if (!m_window.isValid() || m_window.hasExpired(m_windowMs)) {
    m_window.start();
    m_restartCount = 0;
  }
  ++m_restartCount;
  return m_restartCount <= m_maxRestarts ? Decision::Restart : Decision::GiveUp;
}
