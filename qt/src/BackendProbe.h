#pragma once

#include <QtGlobal>

#include <functional>

class QObject;

namespace BackendProbe {

// Polls 127.0.0.1:port until it accepts a connection, then calls onReady.
// Calls onTimeout if the backend never appears. All pending timers and sockets
// are children of owner and are cancelled when owner is destroyed.
void waitFor(QObject *owner, quint16 port, int timeoutMs,
             std::function<void()> onReady, std::function<void()> onTimeout,
             int pollIntervalMs = 150);

} // namespace BackendProbe
