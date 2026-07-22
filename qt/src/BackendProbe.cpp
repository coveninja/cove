#include "BackendProbe.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QObject>
#include <QTcpSocket>
#include <QTimer>

#include <memory>
#include <utility>

namespace BackendProbe {

void waitFor(QObject *owner, quint16 port, int timeoutMs,
             std::function<void()> onReady, std::function<void()> onTimeout,
             int pollIntervalMs) {
  auto *timer = new QTimer(owner);
  timer->setInterval(qMax(1, pollIntervalMs));
  auto elapsed = std::make_shared<QElapsedTimer>();
  auto settled = std::make_shared<bool>(false);
  elapsed->start();

  QObject::connect(timer, &QTimer::timeout, timer,
                   [timer, port, timeoutMs, onReady = std::move(onReady),
                    onTimeout = std::move(onTimeout), elapsed, settled]() {
                     if (*settled)
                       return;
                     if (elapsed->hasExpired(qMax(0, timeoutMs))) {
                       *settled = true;
                       timer->stop();
                       timer->deleteLater();
                       onTimeout();
                       return;
                     }

                     // Multiple probes may overlap when a connection stalls.
                     // Parenting each one to the timer ensures completion or
                     // owner destruction frees all of them together.
                     auto *probe = new QTcpSocket(timer);
                     QObject::connect(probe, &QTcpSocket::connected, timer,
                                      [timer, probe, onReady, settled]() {
                                        if (*settled)
                                          return;
                                        *settled = true;
                                        timer->stop();
                                        timer->deleteLater();
                                        probe->abort();
                                        probe->deleteLater();
                                        onReady();
                                      });
                     QObject::connect(probe, &QTcpSocket::errorOccurred, probe,
                                      [probe]() { probe->deleteLater(); });
                     probe->connectToHost(QHostAddress::LocalHost, port);
                   });
  timer->start();
}

} // namespace BackendProbe
